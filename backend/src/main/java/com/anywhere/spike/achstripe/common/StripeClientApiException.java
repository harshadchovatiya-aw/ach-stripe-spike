package com.anywhere.spike.achstripe.common;

import com.stripe.exception.StripeException;

/** Same idea as com.acti.payment.stripe.error.StripeClientApiException in fullpayments. */
public class StripeClientApiException extends RuntimeException {
    public StripeClientApiException(StripeException cause) {
        super("Stripe error: " + cause.getMessage(), cause);
    }
}
