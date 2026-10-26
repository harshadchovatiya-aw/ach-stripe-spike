package com.anywhere.spike.achstripe.fullpayments.facade;

import com.anywhere.spike.achstripe.common.BadRequestException;
import com.anywhere.spike.achstripe.common.Money;
import com.anywhere.spike.achstripe.common.RequestOptionsProvider;
import com.anywhere.spike.achstripe.fullpayments.StripeGateway;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Copy of fullpayments RefundSubscriptionPaymentCommandHandler (/api/braintree/refundTransaction for pi_...).
 *
 * <p>ACH (P9): an ACH payment cannot be refunded while it is still processing, and ACH refunds are asynchronous
 * (pending → succeeded / failed). The real code saves every refund as "Settled" immediately; here the refund's
 * Stripe status is stored and updated by the refund.updated / refund.failed webhook.
 */
@Component
public class RefundSubscriptionPaymentCommandHandler {

    private final StripeGateway stripeGateway;
    private final RequestOptionsProvider requestOptionsProvider;
    private final TransactionJDORepository transactionJDORepository;

    public RefundSubscriptionPaymentCommandHandler(
            StripeGateway stripeGateway,
            RequestOptionsProvider requestOptionsProvider,
            TransactionJDORepository transactionJDORepository) {
        this.stripeGateway = stripeGateway;
        this.requestOptionsProvider = requestOptionsProvider;
        this.transactionJDORepository = transactionJDORepository;
    }

    public TransactionJDO refundSubscriptionPayment(TransactionJDO original, String amount) {
        RequestOptions options = requestOptionsProvider.getRequestOptions(original.getBusinessId());
        PaymentIntent paymentIntent = stripeGateway.retrievePaymentIntent(original.getTransactionId(), options);
        if (CapturePaymentIntentCommandHandler.STATUS_PROCESSING.equals(paymentIntent.getStatus())) {
            throw new BadRequestException("The ACH payment " + paymentIntent.getId()
                    + " is still processing – it can only be refunded after it settles");
        }
        RefundCreateParams params = RefundCreateParams.builder()
                .setAmount(Money.toMinorUnits(Money.parse(amount)))
                .setPaymentIntent(original.getTransactionId())
                .build();
        Refund refund = stripeGateway.createRefund(params, options);

        TransactionJDO jdo = new TransactionJDO();
        jdo.setAmount(amount);
        jdo.setBusinessId(original.getBusinessId());
        jdo.setTransactionId(refund.getId());
        jdo.setPaymentDate(String.valueOf(Instant.now().toEpochMilli()));
        jdo.setLastModifiedDate(Instant.now());
        jdo.setSourceType("braintree");
        jdo.setCustomerKey(original.getCustomerKey());
        jdo.setReferenceTransaction(original.getTransactionId());
        jdo.setProfileId(original.getProfileId());
        jdo.setCurrency(original.getCurrency());
        jdo.setPaymentMethodType(original.getPaymentMethodType());
        jdo.setResponseStatus(refundStatus(refund.getStatus()));
        return transactionJDORepository.save(jdo);
    }

    /** Stripe refund status → TransactionJDO responseStatus ("Settled" is what the real code always writes). */
    public static String refundStatus(String stripeStatus) {
        if ("succeeded".equals(stripeStatus)) {
            return "Settled";
        }
        return stripeStatus; // pending | requires_action | failed | canceled
    }
}
