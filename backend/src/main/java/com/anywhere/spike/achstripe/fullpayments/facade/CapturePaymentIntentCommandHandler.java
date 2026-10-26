package com.anywhere.spike.achstripe.fullpayments.facade;

import com.anywhere.spike.achstripe.common.Money;
import com.anywhere.spike.achstripe.common.RequestOptionsProvider;
import com.anywhere.spike.achstripe.fullpayments.StripeGateway;
import com.anywhere.spike.achstripe.fullpayments.intent.PlatformIntentCache;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Copy of fullpayments CapturePaymentIntentCommandHandler – used by /api/braintree/createTransaction when the
 * "nonce" is a platform PaymentIntent (one-time payment).
 */
@Component
public class CapturePaymentIntentCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(CapturePaymentIntentCommandHandler.class);

    private final StripeGateway stripeGateway;
    private final RequestOptionsProvider requestOptionsProvider;
    private final PlatformIntentCache cache;
    private final TransactionJDORepository transactionJDORepository;

    public CapturePaymentIntentCommandHandler(
            StripeGateway stripeGateway,
            RequestOptionsProvider requestOptionsProvider,
            PlatformIntentCache cache,
            TransactionJDORepository transactionJDORepository) {
        this.stripeGateway = stripeGateway;
        this.requestOptionsProvider = requestOptionsProvider;
        this.cache = cache;
        this.transactionJDORepository = transactionJDORepository;
    }

    public record CaptureAndSaveResult(TransactionJDO transactionJDO, CardInfo cardInfo) {}

    public CaptureAndSaveResult captureAndSave(
            PlatformIntentCache.PlatformPaymentIntentMetadata metadata, String amount, String productKey) {
        log.info("Capturing platform payment intent: {} for amount: {}", metadata.paymentIntentId(), amount);
        RequestOptions requestOptions = requestOptionsProvider.getRequestOptions(metadata.businessId());

        // ACH (P4): a us_bank_account PI is already "processing" after confirm – do NOT capture it. Verify the amount,
        // save the TransactionJDO as PENDING and return success (like Braintree submitForSettlement).
        PaymentIntent paymentIntent = capturePaymentIntent(metadata.paymentIntentId(), amount, requestOptions);
        cache.evictPaymentIntent(metadata.paymentIntentId());

        TransactionJDO jdo = new TransactionJDO();
        jdo.setTransactionId(paymentIntent.getId());
        jdo.setAmount(amount);
        jdo.setBusinessId(metadata.businessId());
        jdo.setProductKey(productKey);
        jdo.setPaymentDate(String.valueOf(paymentIntent.getCreated()));
        jdo.setLastModifiedDate(Instant.now());
        jdo.setResponseCode("COMPLETED");
        jdo.setCurrency(paymentIntent.getCurrency().toUpperCase(Locale.US));
        transactionJDORepository.save(jdo);

        log.info("Captured payment intent: {}", paymentIntent.getId());
        return new CaptureAndSaveResult(jdo, CardInfo.extractCardInfo(paymentIntent.getPaymentMethodObject()));
    }

    /** Checks that jBilling's amount equals the authorized PI amount, then captures. */
    public PaymentIntent capturePaymentIntent(String paymentIntentId, String amount, RequestOptions requestOptions) {
        PaymentIntent retrieved = stripeGateway.retrievePaymentIntent(paymentIntentId, requestOptions);
        BigDecimal intentAmount = Money.fromMinorUnits(retrieved.getAmount());
        BigDecimal requestedAmount = Money.parse(amount);
        if (intentAmount.compareTo(requestedAmount) != 0) {
            throw new IllegalStateException("The amount requested (" + requestedAmount
                    + ") is not equal to the amount of the payment intent (" + intentAmount + ") for PI: "
                    + retrieved.getId());
        }
        return stripeGateway.capture(retrieved, requestOptions);
    }
}
