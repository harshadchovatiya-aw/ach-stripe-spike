package com.anywhere.spike.achstripe.fullpayments.facade;

import com.stripe.model.PaymentMethod;
import java.util.Optional;

/**
 * ACH (P6): what the facade returns to jBilling for a us_bank_account payment method, next to (instead of) the
 * card-only CardInfo – whose fallback would otherwise report a fake 1111 / visa card.
 */
public record BankInfo(String bankName, String last4, String routingNumber, String accountType, String accountHolderName) {

    public static Optional<BankInfo> extract(PaymentMethod paymentMethod) {
        return Optional.ofNullable(paymentMethod)
                .filter(pm -> pm.getUsBankAccount() != null)
                .map(pm -> new BankInfo(
                        pm.getUsBankAccount().getBankName(),
                        pm.getUsBankAccount().getLast4(),
                        pm.getUsBankAccount().getRoutingNumber(),
                        pm.getUsBankAccount().getAccountType(),
                        pm.getBillingDetails() != null ? pm.getBillingDetails().getName() : null));
    }

    public static boolean isBankAccount(PaymentMethod paymentMethod) {
        return paymentMethod != null && "us_bank_account".equals(paymentMethod.getType());
    }
}
