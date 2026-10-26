package com.anywhere.spike.achstripe.fullpayments.webhook;

import com.anywhere.spike.achstripe.common.SpikeProperties;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Dispute;
import com.stripe.model.Event;
import com.stripe.model.Mandate;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentMethod;
import com.stripe.model.Refund;
import com.stripe.model.SetupIntent;
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
 * ACH (P7): payment_intent.processing / succeeded / payment_failed, charge.dispute.created, mandate.updated,
 * refund.updated / refund.failed are handled by {@link AchWebhookEventHandler}, which updates TransactionJDO and
 * notifies the jBilling hosted API (which creates the DS task). setup_intent.* events are only logged.
 */
@RestController
public class StripeWebhookController {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);

    private final SpikeProperties properties;
    private final UpdatePlatformCustomerPaymentMethodOnPaymentMethodAttachedHandler paymentMethodAttachedHandler;
    private final WebhookEventLog eventLog;
    private final AchWebhookEventHandler achHandler;

    public StripeWebhookController(
            SpikeProperties properties,
            UpdatePlatformCustomerPaymentMethodOnPaymentMethodAttachedHandler paymentMethodAttachedHandler,
            WebhookEventLog eventLog,
            AchWebhookEventHandler achHandler) {
        this.properties = properties;
        this.paymentMethodAttachedHandler = paymentMethodAttachedHandler;
        this.eventLog = eventLog;
        this.achHandler = achHandler;
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
        String objectId = objectId(object);
        String outcome;
        try {
            outcome = dispatch(event, object);
        } catch (Exception e) {
            log.error("Webhook handling failed for {} {}", event.getId(), event.getType(), e);
            outcome = "ERROR: " + e.getMessage();
        }
        log.info("Webhook {} {} {} -> {}", event.getId(), event.getType(), objectId, outcome);
        eventLog.add(new WebhookEventLog.Entry(Instant.now(), event.getId(), event.getType(), objectId, outcome));
        return ResponseEntity.ok(Map.of("received", true));
    }

    private String dispatch(Event event, StripeObject object) {
        String id = event.getId();
        return switch (event.getType()) {
            case "payment_method.attached" -> object instanceof PaymentMethod pm
                    ? paymentMethodAttachedHandler.handle(pm)
                    : "Could not deserialize PaymentMethod";
            // ---- ACH (P7) ----
            case "payment_intent.processing" -> object instanceof PaymentIntent pi ? achHandler.onProcessing(pi) : bad();
            case "payment_intent.succeeded" -> object instanceof PaymentIntent pi ? achHandler.onSucceeded(id, pi) : bad();
            case "payment_intent.payment_failed" ->
                object instanceof PaymentIntent pi ? achHandler.onPaymentFailed(id, pi) : bad();
            case "charge.dispute.created" -> object instanceof Dispute d ? achHandler.onDisputeCreated(id, d) : bad();
            case "mandate.updated" -> object instanceof Mandate m ? achHandler.onMandateUpdated(id, m) : bad();
            case "refund.updated", "refund.failed" -> object instanceof Refund r ? achHandler.onRefundUpdated(id, r) : bad();
            case "setup_intent.succeeded", "setup_intent.requires_action", "setup_intent.setup_failed" ->
                object instanceof SetupIntent si
                        ? "SetupIntent " + si.getId() + " " + si.getStatus() + " (logged only – microdeposits are phase 2)"
                        : bad();
            default -> "Not handled by the platform webhook";
        };
    }

    private static String bad() {
        return "Could not deserialize the event object";
    }

    private static String objectId(StripeObject object) {
        if (object instanceof PaymentMethod o) {
            return o.getId();
        }
        if (object instanceof PaymentIntent o) {
            return o.getId();
        }
        if (object instanceof SetupIntent o) {
            return o.getId();
        }
        if (object instanceof Dispute o) {
            return o.getId();
        }
        if (object instanceof Mandate o) {
            return o.getId();
        }
        if (object instanceof Refund o) {
            return o.getId();
        }
        return null;
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
