package com.hardik.billing.webhook.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hardik.billing.payment.PaymentStatus;
import com.hardik.billing.payment.service.PaymentService;
import com.hardik.billing.webhook.WebhookEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    private final WebhookEventRepository events;
    private final PaymentService payments;

    public WebhookService(WebhookEventRepository events, PaymentService payments) {
        this.events = events;
        this.payments = payments;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean process(String id, String type, String rawBody, JsonNode object) {
        // The insert waits on an uncommitted duplicate. The row lock also serializes retries of an existing event.
        events.insertIfMissing(id, type, rawBody);
        var event = events.findByIdForUpdate(id).orElseThrow();
        if (event.isProcessed()) {
            log.info("Stripe event {} was already processed.", id);
            return true;
        }

        PaymentStatus target = switch (type) {
            case "payment_intent.amount_capturable_updated" -> PaymentStatus.AUTHORIZED;
            case "payment_intent.succeeded" -> PaymentStatus.CAPTURED;
            case "payment_intent.payment_failed" -> PaymentStatus.FAILED;
            case "payment_intent.canceled" -> PaymentStatus.VOIDED;
            case "charge.refunded" -> PaymentStatus.REFUNDED;
            default -> null;
        };
        if (target != null) {
            UUID paymentId = parsePaymentId(object.path("metadata").path("paymentId").textValue());
            String reference = processorReference(object, target);
            String failure = target == PaymentStatus.FAILED ? failureReason(object.path("last_payment_error")) : null;
            if (!payments.applyWebhook(paymentId, reference, target, failure, type)) {
                log.warn("No payment matched Stripe event {}.", id);
                return false;
            }
        }
        event.markProcessed(Instant.now());
        return true;
    }

    private static String processorReference(JsonNode object, PaymentStatus target) {
        if (target == PaymentStatus.REFUNDED) {
            JsonNode intent = object.path("payment_intent");
            return intent.isObject() ? intent.path("id").textValue() : intent.textValue();
        }
        return object.path("id").textValue();
    }

    private static UUID parsePaymentId(String value) {
        if (value != null) {
            try {
                UUID id = UUID.fromString(value);
                return id.toString().equalsIgnoreCase(value) ? id : null;
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String failureReason(JsonNode error) {
        String declineCode = error.path("decline_code").textValue();
        if (declineCode != null && declineCode.matches("[a-z0-9_]{1,100}")) {
            return declineCode;
        }
        String code = error.path("code").textValue();
        return code != null && code.matches("[a-z0-9_]{1,100}") ? code : "payment_failed";
    }
}
