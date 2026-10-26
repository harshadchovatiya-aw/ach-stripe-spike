package com.anywhere.spike.achstripe.common;

import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spike")
public record SpikeProperties(String fullpaymentsBaseUrl, Stripe stripe, Map<String, Brand> brands) {

    public record Stripe(String webhookSecret, String webhookSecretFile, Map<String, StripeAccount> businesses) {}

    /** Keys of one Stripe account, selected by the FullPayments business id. */
    public record StripeAccount(String secretKey, String publishableKey) {}

    /** jBilling brand configuration ({brandId}.stripe.businessid, {brandId}.btbusinessid, ...). */
    public record Brand(
            String name, String stripeBusinessId, String btBusinessId, String currency, String currencySymbol) {}

    public Optional<StripeAccount> stripeAccount(String businessId) {
        return Optional.ofNullable(stripe.businesses()).map(m -> m.get(businessId));
    }

    public Brand brand(String brandId) {
        Brand brand = brands == null ? null : brands.get(brandId);
        if (brand == null) {
            throw new BadRequestException("Unknown brandId: " + brandId);
        }
        return brand;
    }

    /** Reverse lookup used by the facade: jBilling sends the Braintree business id. */
    public Optional<Brand> brandByBtBusinessId(String btBusinessId) {
        return brands.values().stream()
                .filter(b -> b.btBusinessId().equals(btBusinessId))
                .findFirst();
    }
}
