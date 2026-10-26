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
 *
 * <p>ACH (P4): only PIs in requires_capture are captured. A us_bank_account PI is already "processing" after the
 * customer confirms it (automatic capture) – it is recorded as PENDING and accepted, like Braintree's
 * submitForSettlement. Settlement / failure arrives later through the webhook.
 */
@Component
public class CapturePaymentIntentCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(CapturePaymentIntentCommandHandler.class);

    public static final String STATUS_REQUIRES_CAPTURE = "requires_capture";
    public static final String STATUS_PROCESSING = "processing";
    public static final String STATUS_SUCCEEDED = "succeeded";

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

    public record CaptureAndSaveResult(TransactionJDO transactionJDO, CardInfo cardInfo, BankInfo bankInfo) {}

    public CaptureAndSaveResult captureAndSave(
            PlatformIntentCache.PlatformPaymentIntentMetadata metadata, String amount, String productKey) {
        log.info("Capturing platform payment intent: {} for amount: {}", metadata.paymentIntentId(), amount);
        RequestOptions requestOptions = requestOptionsProvider.getRequestOptions(metadata.businessId());

        PaymentIntent paymentIntent = capturePaymentIntent(metadata.paymentIntentId(), amount, requestOptions);
        cache.evictPaymentIntent(metadata.paymentIntentId());

        TransactionJDO jdo = new TransactionJDO();
        jdo.setTransactionId(paymentIntent.getId());
        jdo.setAmount(amount);
        jdo.setBusinessId(metadata.businessId());
        jdo.setProductKey(productKey);
        jdo.setCustomerKey(paymentIntent.getMetadata() != null ? paymentIntent.getMetadata().get("jBillingCustomerKey") : null);
        jdo.setPaymentDate(String.valueOf(paymentIntent.getCreated()));
        jdo.setLastModifiedDate(Instant.now());
        jdo.setResponseCode(responseCodeFor(paymentIntent));
        jdo.setPaymentMethodType(paymentMethodType(paymentIntent));
        jdo.setCurrency(paymentIntent.getCurrency().toUpperCase(Locale.US));
        transactionJDORepository.save(jdo);

        log.info("Payment intent {} recorded as {}", paymentIntent.getId(), jdo.getResponseCode());
        var paymentMethod = paymentIntent.getPaymentMethodObject();
        boolean bank = BankInfo.isBankAccount(paymentMethod);
        return new CaptureAndSaveResult(
                jdo, bank ? null : CardInfo.extractCardInfo(paymentMethod), BankInfo.extract(paymentMethod).orElse(null));
    }

    /**
     * Checks that jBilling's amount equals the PI amount, then captures it if it is waiting for capture (cards).
     * Returns the PI with payment_method expanded.
     */
    public PaymentIntent capturePaymentIntent(String paymentIntentId, String amount, RequestOptions requestOptions) {
        PaymentIntent retrieved = stripeGateway.retrieveWithExpandedPaymentMethod(paymentIntentId, requestOptions);
        BigDecimal intentAmount = Money.fromMinorUnits(retrieved.getAmount());
        BigDecimal requestedAmount = Money.parse(amount);
        if (intentAmount.compareTo(requestedAmount) != 0) {
            throw new IllegalStateException("The amount requested (" + requestedAmount
                    + ") is not equal to the amount of the payment intent (" + intentAmount + ") for PI: "
                    + retrieved.getId());
        }
        String status = retrieved.getStatus();
        if (STATUS_REQUIRES_CAPTURE.equals(status)) {
            return stripeGateway.capture(retrieved, requestOptions);
        }
        if (STATUS_PROCESSING.equals(status) || STATUS_SUCCEEDED.equals(status)) {
            // ACH (P4): nothing to capture – the bank debit is already submitted (or settled).
            log.info("PI {} is {} – no capture needed ({})", retrieved.getId(), status, paymentMethodType(retrieved));
            return retrieved;
        }
        if ("requires_payment_method".equals(status) && retrieved.getLastPaymentError() != null) {
            // ACH: in test mode the bank return can arrive before jBilling records the payment (the
            // payment_intent.payment_failed webhook then finds no TransactionJDO) – reject it here instead.
            throw new IllegalStateException("PaymentIntent " + retrieved.getId() + " failed: "
                    + retrieved.getLastPaymentError().getMessage());
        }
        throw new IllegalStateException("PaymentIntent " + retrieved.getId() + " cannot be charged in status " + status
                + (STATUS_REQUIRES_ACTION.equals(status) ? " (bank account not verified yet – microdeposits)" : ""));
    }

    private static final String STATUS_REQUIRES_ACTION = "requires_action";

    /** COMPLETED for succeeded (cards after capture), PENDING for processing (ACH submitted, not settled yet). */
    public static String responseCodeFor(PaymentIntent paymentIntent) {
        String status = paymentIntent.getStatus();
        if (STATUS_SUCCEEDED.equals(status)) {
            return "COMPLETED";
        }
        if (STATUS_PROCESSING.equals(status)) {
            return "PENDING";
        }
        return status == null ? "UNKNOWN" : status.toUpperCase(Locale.US);
    }

    public static String paymentMethodType(PaymentIntent paymentIntent) {
        if (paymentIntent.getPaymentMethodObject() != null) {
            return paymentIntent.getPaymentMethodObject().getType();
        }
        return null;
    }
}
