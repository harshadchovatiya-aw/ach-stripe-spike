package com.anywhere.spike.achstripe.fullpayments.webhook;

import com.anywhere.spike.achstripe.common.SpikeProperties;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.PaymentMethod;
import com.stripe.model.StripeObject;
import com.stripe.net.Webhook;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * FullPayments platform webhook (/webhook/stripe/platform/v2026-01-28 → StripeWebhookHandler).
 * For the platform account the real code only acts on payment_method.attached.
 *
 * ACH (P7): add payment_intent.succeeded / payment_intent.payment_failed / charge.dispute.created /
 * mandate.updated / refund.updated / refund.failed / setup_intent.* here, update TransactionJDO and notify the
 * jBilling hosted API (which creates the DS task).
 */
@RestController
public class StripeWebhookController {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);

    private final SpikeProperties properties;
    private final UpdatePlatformCustomerPaymentMethodOnPaymentMethodAttachedHandler paymentMethodAttachedHandler;
    private final WebhookEventLog eventLog;

    public StripeWebhookController(
            SpikeProperties properties,
            UpdatePlatformCustomerPaymentMethodOnPaymentMethodAttachedHandler paymentMethodAttachedHandler,
            WebhookEventLog eventLog) {
        this.properties = properties;
        this.paymentMethodAttachedHandler = paymentMethodAttachedHandler;
        this.eventLog = eventLog;
    }

    @PostMapping("/webhook/stripe/platform/v2026-01-28")
    public ResponseEntity<Map<String, Object>> platformWebhook(
            @RequestHeader("Stripe-Signature") String signature, @RequestBody String payload) {
        String secret = webhookSecret().orElse(null);
        if (secret == null) {
            log.warn("Webhook received but no signing secret is configured – ignoring");
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Set STRIPE_WEBHOOK_SECRET or run the stripe-cli container"));
        }
        Event event;
        try {
            event = Webhook.constructEvent(payload, signature, secret);
        } catch (SignatureVerificationException e) {
            log.warn("Invalid Stripe webhook signature: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("message", "Invalid signature"));
        }

        StripeObject object = deserialize(event);
        String objectId = object instanceof PaymentMethod pm ? pm.getId() : null;
        String outcome;
        switch (event.getType()) {
            case "payment_method.attached" -> outcome = object instanceof PaymentMethod pm
                    ? paymentMethodAttachedHandler.handle(pm)
                    : "Could not deserialize PaymentMethod";
            default -> outcome = "Not handled by the platform webhook (same as fullpayments today)";
        }
        log.info("Webhook {} {} {} -> {}", event.getId(), event.getType(), objectId, outcome);
        eventLog.add(new WebhookEventLog.Entry(Instant.now(), event.getId(), event.getType(), objectId, outcome));
        return ResponseEntity.ok(Map.of("received", true));
    }

    @GetMapping("/spike/webhook-events")
    public Object events() {
        return eventLog.list();
    }

    /** Same as StripeEventDataObjectExtractor.extractTypedDataObject (deserializeUnsafe). */
    private static StripeObject deserialize(Event event) {
        try {
            return event.getDataObjectDeserializer().deserializeUnsafe();
        } catch (Exception e) {
            log.warn("Could not deserialize event {}: {}", event.getId(), e.getMessage());
            return null;
        }
    }

    private Optional<String> webhookSecret() {
        String direct = properties.stripe().webhookSecret();
        if (direct != null && !direct.isBlank()) {
            return Optional.of(direct.trim());
        }
        String file = properties.stripe().webhookSecretFile();
        if (file != null && !file.isBlank()) {
            try {
                Path path = Path.of(file);
                if (Files.exists(path)) {
                    String value = Files.readString(path).trim();
                    return value.isEmpty() ? Optional.empty() : Optional.of(value);
                }
            } catch (IOException e) {
                log.warn("Cannot read webhook secret file {}: {}", file, e.getMessage());
            }
        }
        return Optional.empty();
    }
}
