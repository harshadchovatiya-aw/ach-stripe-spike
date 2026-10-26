package com.anywhere.spike.achstripe.common;

import com.stripe.net.RequestOptions;
import org.springframework.stereotype.Component;

/**
 * Mirrors fullpayments StripeRequestOptionsProvider#getRequestOptions(BusinessId) and PlatformPublicKeyProvider:
 * the FullPayments business id (X-Business-ID) selects the Stripe account keys. Platform flow only, so no
 * Stripe-Account header (no connected accounts).
 */
@Component
public class RequestOptionsProvider {

    private final SpikeProperties properties;

    public RequestOptionsProvider(SpikeProperties properties) {
        this.properties = properties;
    }

    public RequestOptions getRequestOptions(String businessId) {
        return RequestOptions.builder().setApiKey(secretKey(businessId)).build();
    }

    public RequestOptions getRequestOptions(String businessId, String idempotencyKey) {
        return RequestOptions.builder()
                .setApiKey(secretKey(businessId))
                .setIdempotencyKey(idempotencyKey)
                .build();
    }

    public String getPublicKey(String businessId) {
        var account = properties.stripeAccount(businessId)
                .orElseThrow(() -> new BadRequestException("Invalid business id: " + businessId));
        if (account.publishableKey() == null || account.publishableKey().isBlank()) {
            throw new BadRequestException("STRIPE_PUBLISHABLE_KEY is not configured");
        }
        return account.publishableKey();
    }

    private String secretKey(String businessId) {
        var account = properties.stripeAccount(businessId)
                .orElseThrow(() -> new BadRequestException("Invalid business id: " + businessId));
        if (account.secretKey() == null || account.secretKey().isBlank()) {
            throw new BadRequestException("STRIPE_SECRET_KEY is not configured");
        }
        return account.secretKey();
    }
}
