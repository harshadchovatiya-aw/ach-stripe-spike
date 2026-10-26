package com.anywhere.spike.achstripe.fullpayments.facade;

import com.anywhere.spike.achstripe.common.Money;
import com.anywhere.spike.achstripe.common.RequestOptionsProvider;
import com.anywhere.spike.achstripe.fullpayments.StripeGateway;
import com.stripe.model.Refund;
import com.stripe.param.RefundCreateParams;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** Copy of fullpayments RefundSubscriptionPaymentCommandHandler (/api/braintree/refundTransaction for pi_...). */
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
        // ACH (P9): reject while the ACH payment is still processing; ACH refunds are asynchronous, so do not
        // record them as "Settled" immediately (update on refund.updated / refund.failed).
        RefundCreateParams params = RefundCreateParams.builder()
                .setAmount(Money.toMinorUnits(Money.parse(amount)))
                .setPaymentIntent(original.getTransactionId())
                .build();
        Refund refund = stripeGateway.createRefund(params, requestOptionsProvider.getRequestOptions(original.getBusinessId()));

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
        jdo.setResponseStatus("Settled");
        return transactionJDORepository.save(jdo);
    }
}
