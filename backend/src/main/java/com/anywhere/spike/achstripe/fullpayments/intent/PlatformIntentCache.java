package com.anywhere.spike.achstripe.fullpayments.intent;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * In-memory stand-in for the three Redis caches fullpayments uses in the platform flow:
 * <ul>
 *   <li>platformPaymentIntentCache  – pi_id -> businessId (PlatformPaymentIntentCacheConfig, TTL 7 days)</li>
 *   <li>platformSetupIntentCache    – seti_id -> businessId, only for signup (no customerKey) setup intents</li>
 *   <li>pendingCapturePaymentIntentCache – jBilling customerKey -> pi_id waiting to be captured by the next
 *       /api/braintree/processPayment call (signup / "pay with new card and save")</li>
 * </ul>
 * The PI / SI id is what the UI later sends to jBilling as the Braintree-style "nonce".
 */
@Component
public class PlatformIntentCache {

    public record PlatformPaymentIntentMetadata(String paymentIntentId, String businessId) {}

    public record PlatformSetupIntentMetadata(String setupIntentId, String businessId) {}

    public record PendingCapturePaymentIntentMetadata(String paymentIntentId, String businessId) {}

    private final Map<String, PlatformPaymentIntentMetadata> paymentIntents = new ConcurrentHashMap<>();
    private final Map<String, PlatformSetupIntentMetadata> setupIntents = new ConcurrentHashMap<>();
    private final Map<String, PendingCapturePaymentIntentMetadata> pendingCaptures = new ConcurrentHashMap<>();

    public void putPaymentIntent(PlatformPaymentIntentMetadata metadata) {
        paymentIntents.put(metadata.paymentIntentId(), metadata);
    }

    public Optional<PlatformPaymentIntentMetadata> getPaymentIntent(String id) {
        return Optional.ofNullable(id).map(paymentIntents::get);
    }

    public void evictPaymentIntent(String id) {
        paymentIntents.remove(id);
    }

    public void putSetupIntent(PlatformSetupIntentMetadata metadata) {
        setupIntents.put(metadata.setupIntentId(), metadata);
    }

    public Optional<PlatformSetupIntentMetadata> getSetupIntent(String id) {
        return Optional.ofNullable(id).map(setupIntents::get);
    }

    public void putPendingCapture(String jBillingCustomerKey, PendingCapturePaymentIntentMetadata metadata) {
        pendingCaptures.put(jBillingCustomerKey, metadata);
    }

    public Optional<PendingCapturePaymentIntentMetadata> getPendingCapture(String jBillingCustomerKey) {
        return Optional.ofNullable(jBillingCustomerKey).map(pendingCaptures::get);
    }

    public void evictPendingCapture(String jBillingCustomerKey) {
        pendingCaptures.remove(jBillingCustomerKey);
    }

    public Map<String, Object> snapshot() {
        return Map.of(
                "platformPaymentIntentCache", Map.copyOf(paymentIntents),
                "platformSetupIntentCache", Map.copyOf(setupIntents),
                "pendingCapturePaymentIntentCache", Map.copyOf(pendingCaptures));
    }
}
