package com.anywhere.spike.achstripe.fullpayments.facade;

import com.stripe.model.PaymentMethod;
import java.util.Optional;

/**
 * Copy of fullpayments com.acti.braintree.Helper.CardInfo – what the facade returns to jBilling ("cardInfo").
 * Note the fallback: for anything that is not a card it returns a fake 1111 / visa / 12/2036.
 * ACH (P6): for us_bank_account the facade now returns a {@link BankInfo} instead (cardInfo is left out).
 */
public record CardInfo(String last4, String expirationDate, String type, String cardHolderName) {

    public static CardInfo extractCardInfo(PaymentMethod paymentMethod) {
        return Optional.ofNullable(paymentMethod)
                .map(PaymentMethod::getCard)
                .map(card -> new CardInfo(
                        card.getLast4(),
                        formatExpirationDate(card.getExpMonth(), card.getExpYear()),
                        card.getBrand(),
                        null))
                .orElse(new CardInfo("1111", "12/2036", "visa", null));
    }

    private static String formatExpirationDate(Long expMonth, Long expYear) {
        if (expMonth == null || expYear == null) {
            return "12/2036";
        }
        return String.format("%02d/%d", expMonth, expYear);
    }
}
