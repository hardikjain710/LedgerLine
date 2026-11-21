package com.hardik.billing.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hardik.billing.common.BillingException;
import com.hardik.billing.common.ErrorCode;
import com.hardik.billing.payment.processor.StripeGateway;
import com.hardik.billing.webhook.service.WebhookService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StripeWebhookController {

    private final StripeGateway gateway;
    private final WebhookService webhooks;
    private final ObjectMapper json;

    public StripeWebhookController(StripeGateway gateway, WebhookService webhooks, ObjectMapper json) {
        this.gateway = gateway;
        this.webhooks = webhooks;
        this.json = json;
    }

    @PostMapping("/webhooks/stripe")
    public ResponseEntity<Void> receive(@RequestBody String rawBody,
                                        @RequestHeader(name = "Stripe-Signature", required = false) String signature) {
        gateway.verifyWebhook(rawBody, signature);
        var event = readEvent(rawBody);
        boolean processed;
        try {
            processed = webhooks.process(event.path("id").textValue(), event.path("type").textValue(),
                    rawBody, event.path("data").path("object"));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Stripe webhook processing failed.", exception);
        }
        if (!processed) {
            // The transaction has committed the unprocessed row. Stripe can retry this event.
            throw new BillingException(ErrorCode.NOT_FOUND, "No payment matched the Stripe event.");
        }
        return ResponseEntity.ok().build();
    }

    private JsonNode readEvent(String rawBody) {
        try {
            JsonNode event = json.readTree(rawBody);
            String id = event.path("id").textValue();
            String type = event.path("type").textValue();
            if (id == null || id.isBlank() || id.length() > 255 || type == null || type.isBlank() || type.length() > 100) {
                throw new BillingException(ErrorCode.VALIDATION_ERROR, "Stripe event ID or type is invalid.");
            }
            if (event.path("livemode").asBoolean()) {
                throw new BillingException(ErrorCode.VALIDATION_ERROR, "Only Stripe test-mode events are accepted.");
            }
            return event;
        } catch (JsonProcessingException exception) {
            throw new BillingException(ErrorCode.VALIDATION_ERROR, "Stripe webhook body is invalid.");
        }
    }
}
