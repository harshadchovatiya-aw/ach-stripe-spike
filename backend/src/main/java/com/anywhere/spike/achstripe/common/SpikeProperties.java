package com.anywhere.spike.achstripe.common;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spike")
public record SpikeProperties(
        String fullpaymentsBaseUrl, String hostedApiBaseUrl, Stripe stripe, Ach ach, Map<String, Brand> brands) {

    public record Stripe(String webhookSecret, String webhookSecretFile, Map<String, StripeAccount> businesses) {}

    /** Keys of one Stripe account, selected by the FullPayments business id. */
    public record StripeAccount(String secretKey, String publishableKey) {}

    /**
     * ACH settings.
     * <ul>
     *   <li>enabledBusinessIds – FullPayments brand gating (P0): ACH options are only added for these Stripe accounts</li>
     *   <li>verificationMethod – instant (Financial Connections only, phase 1) | automatic (instant, falls back to
     *       manual entry + microdeposits) | microdeposits</li>
     *   <li>financialConnectionsPermissions – payment_method (+ balances / ownership if needed)</li>
     *   <li>reverseJbillingPaymentOnFailure – phase 2: also reverse the jBilling payment when ACH fails
     *       (phase 1 / Braintree parity: DS task only)</li>
     * </ul>
     */
    public record Ach(
            List<String> enabledBusinessIds,
            String verificationMethod,
            List<String> financialConnectionsPermissions,
            boolean reverseJbillingPaymentOnFailure) {}

    /**
     * jBilling brand configuration ({brandId}.stripe.businessid, {brandId}.btbusinessid, {brandId}.ach.provider ...).
     */
    public record Brand(
            String name,
            String stripeBusinessId,
            String btBusinessId,
            String currency,
            String currencySymbol,
            String achProvider) {

        /** {brandId}.ach.provider=STRIPE – only AnswerForce US. */
        public boolean isStripeAch() {
            return "STRIPE".equalsIgnoreCase(achProvider);
        }
    }

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

    /** FullPayments P0: is ACH enabled for this Stripe business id? */
    public boolean isAchEnabled(String businessId) {
        return ach != null && ach.enabledBusinessIds() != null && ach.enabledBusinessIds().contains(businessId);
    }
}
