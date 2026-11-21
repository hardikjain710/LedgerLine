package com.hardik.billing.payment.processor;

import com.hardik.billing.common.BillingException;
import com.hardik.billing.common.ErrorCode;
import com.stripe.StripeClient;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.ApiException;
import com.stripe.exception.CardException;
import com.stripe.exception.RateLimitException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.PaymentIntentCancelParams;
import com.stripe.param.PaymentIntentCaptureParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class StripeGateway {

    private static final Logger log = LoggerFactory.getLogger(StripeGateway.class);

    private final StripeClient client;
    private final String webhookSecret;

    public StripeGateway(ConfigurableEnvironment environment,
                         @Value("${billing.processor:simulated}") String processor) {
        this.webhookSecret = environmentVariable(environment, "STRIPE_WEBHOOK_SECRET");
        if ("stripe".equalsIgnoreCase(processor)) {
            String apiKey = environmentVariable(environment, "STRIPE_API_KEY");
            if (apiKey == null || !apiKey.startsWith("sk_test_") || apiKey.length() == "sk_test_".length()) {
                throw new IllegalStateException("Stripe mode requires STRIPE_API_KEY with an sk_test_ prefix.");
            }
            this.client = new StripeClient(apiKey);
        } else {
            this.client = null;
        }
    }

    public Outcome authorize(String token, long amount, String currency, Map<String, String> metadata, String key) {
        var automaticMethods = PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                .setEnabled(true)
                .setAllowRedirects(PaymentIntentCreateParams.AutomaticPaymentMethods.AllowRedirects.NEVER)
                .build();
        var params = PaymentIntentCreateParams.builder()
                .setCaptureMethod(PaymentIntentCreateParams.CaptureMethod.MANUAL)
                .setConfirm(true)
                .setPaymentMethod(token)
                .setAmount(amount)
                .setCurrency(currency)
                .putAllMetadata(metadata)
                .setAutomaticPaymentMethods(automaticMethods)
                .build();
        return call(() -> {
            var intent = client.v1().paymentIntents().create(params, options(key));
            return new Outcome(intent.getId(), intent.getStatus());
        });
    }

    public Outcome capture(String reference, long amount, String key) {
        var params = PaymentIntentCaptureParams.builder().setAmountToCapture(amount).build();
        return call(() -> {
            var intent = client.v1().paymentIntents().capture(reference, params, options(key));
            return new Outcome(intent.getId(), intent.getStatus());
        });
    }

    public Outcome voidAuthorization(String reference, String key) {
        var params = PaymentIntentCancelParams.builder().build();
        return call(() -> {
            var intent = client.v1().paymentIntents().cancel(reference, params, options(key));
            return new Outcome(intent.getId(), intent.getStatus());
        });
    }

    public Outcome refund(String reference, long amount, String key) {
        var params = RefundCreateParams.builder().setPaymentIntent(reference).setAmount(amount).build();
        return call(() -> {
            var refund = client.v1().refunds().create(params, options(key));
            return new Outcome(reference, refund.getStatus());
        });
    }

    public void verifyWebhook(String payload, String signature) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            log.error("Stripe webhook cannot be verified because STRIPE_WEBHOOK_SECRET is missing.");
            throw new BillingException(ErrorCode.INTERNAL_ERROR, "Stripe webhook verification is unavailable.");
        }
        if (signature == null || signature.isBlank()) {
            throw new BillingException(ErrorCode.VALIDATION_ERROR, "Stripe-Signature is required.");
        }
        try {
            Webhook.constructEvent(payload, signature, webhookSecret);
        } catch (SignatureVerificationException | IllegalArgumentException exception) {
            throw new BillingException(ErrorCode.VALIDATION_ERROR, "Stripe webhook signature or event is invalid.");
        }
    }

    private static String environmentVariable(ConfigurableEnvironment environment, String name) {
        var source = environment.getPropertySources().get(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        return source == null ? null : (String) source.getProperty(name);
    }

    private static RequestOptions options(String key) {
        return RequestOptions.builder().setIdempotencyKey(key).build();
    }

    private static Outcome call(StripeCall operation) {
        try {
            return operation.execute();
        } catch (CardException exception) {
            throw new Failure(FailureType.CARD, safeCode(exception.getDeclineCode(),
                    safeCode(exception.getCode(), "card_declined")));
        } catch (StripeException exception) {
            Integer status = exception.getStatusCode();
            if (exception instanceof ApiConnectionException || exception instanceof ApiException
                    || exception instanceof RateLimitException
                    || (status != null && (status == 429 || status >= 500))) {
                throw new Failure(FailureType.TEMPORARY, "stripe_unavailable");
            }
            throw new Failure(FailureType.REJECTED, safeCode(exception.getCode(), "stripe_request_rejected"));
        }
    }

    private static String safeCode(String code, String fallback) {
        return code != null && code.matches("[a-z0-9_]{1,100}") ? code : fallback;
    }

    @FunctionalInterface
    private interface StripeCall {
        Outcome execute() throws StripeException;
    }

    public record Outcome(String reference, String status) {
    }

    public enum FailureType {
        CARD, TEMPORARY, REJECTED
    }

    public static final class Failure extends RuntimeException {

        private final FailureType type;

        public Failure(FailureType type, String reason) {
            super(reason);
            this.type = type;
        }

        public FailureType type() {
            return type;
        }
    }
}
