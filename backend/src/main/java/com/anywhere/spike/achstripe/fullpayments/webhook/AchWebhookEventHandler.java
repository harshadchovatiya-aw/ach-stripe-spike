package com.anywhere.spike.achstripe.fullpayments.webhook;

import com.anywhere.spike.achstripe.common.Money;
import com.anywhere.spike.achstripe.common.RequestOptionsProvider;
import com.anywhere.spike.achstripe.common.SpikeProperties;
import com.anywhere.spike.achstripe.fullpayments.StripeGateway;
import com.anywhere.spike.achstripe.fullpayments.customer.StripePlatformCustomer;
import com.anywhere.spike.achstripe.fullpayments.customer.StripePlatformCustomerRepository;
import com.anywhere.spike.achstripe.fullpayments.facade.RefundSubscriptionPaymentCommandHandler;
import com.anywhere.spike.achstripe.fullpayments.facade.TransactionJDO;
import com.anywhere.spike.achstripe.fullpayments.facade.TransactionJDORepository;
import com.anywhere.spike.achstripe.fullpayments.webhook.HostedApiNotifier.AchEvent;
import com.anywhere.spike.achstripe.fullpayments.webhook.HostedApiNotifier.AchPaymentStatusNotification;
import com.stripe.model.Dispute;
import com.stripe.model.Mandate;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentMethod;
import com.stripe.model.Refund;
import com.stripe.model.StripeError;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * ACH (P7/P8): new platform webhook handling. None of this exists in fullpayments today – the platform webhook only
 * handles payment_method.attached. Mirrors what the Braintree flow does with TRANSACTION_SETTLEMENT_DECLINED, plus
 * settlement, disputes, revoked mandates and asynchronous refunds.
 */
@Component
public class AchWebhookEventHandler {

    private static final Logger log = LoggerFactory.getLogger(AchWebhookEventHandler.class);
    private static final String US_BANK_ACCOUNT = "us_bank_account";

    private final TransactionJDORepository transactionJDORepository;
    private final StripePlatformCustomerRepository customerRepository;
    private final StripeGateway stripeGateway;
    private final RequestOptionsProvider requestOptionsProvider;
    private final SpikeProperties properties;
    private final HostedApiNotifier notifier;

    public AchWebhookEventHandler(
            TransactionJDORepository transactionJDORepository,
            StripePlatformCustomerRepository customerRepository,
            StripeGateway stripeGateway,
            RequestOptionsProvider requestOptionsProvider,
            SpikeProperties properties,
            HostedApiNotifier notifier) {
        this.transactionJDORepository = transactionJDORepository;
        this.customerRepository = customerRepository;
        this.stripeGateway = stripeGateway;
        this.requestOptionsProvider = requestOptionsProvider;
        this.properties = properties;
        this.notifier = notifier;
    }

    /** payment_intent.processing – the bank debit was submitted. Nothing to do (already PENDING). */
    public String onProcessing(PaymentIntent pi) {
        return "PI " + pi.getId() + " processing (" + pi.getPaymentMethodTypes() + ")";
    }

    /** payment_intent.succeeded – for ACH this is the settlement (~4 business days later). */
    @Transactional
    public String onSucceeded(String eventId, PaymentIntent pi) {
        Optional<TransactionJDO> jdo = transactionJDORepository.findFirstByTransactionId(pi.getId());
        if (jdo.isEmpty()) {
            return "No TransactionJDO for " + pi.getId() + " (yet) – nothing to update";
        }
        TransactionJDO tx = jdo.get();
        boolean wasPending = "PENDING".equals(tx.getResponseCode());
        tx.setResponseCode("COMPLETED");
        tx.setLastModifiedDate(Instant.now());
        transactionJDORepository.save(tx);
        if (!wasPending) {
            return "TransactionJDO " + pi.getId() + " already COMPLETED (card)";
        }
        return "TransactionJDO PENDING → COMPLETED; "
                + notifier.notify(notification(AchEvent.PAYMENT_SUCCEEDED, eventId, pi, tx, null, null));
    }

    /** payment_intent.payment_failed – for ACH: a bank return (R01 insufficient funds, R02 closed, ...). */
    @Transactional
    public String onPaymentFailed(String eventId, PaymentIntent pi) {
        StripeError error = pi.getLastPaymentError();
        String code = error != null ? Optional.ofNullable(error.getDeclineCode()).orElse(error.getCode()) : null;
        String message = error != null ? error.getMessage() : null;
        Optional<TransactionJDO> jdo = transactionJDORepository.findFirstByTransactionId(pi.getId());
        if (jdo.isEmpty()) {
            // e.g. a card declined while the customer was still on the page – nothing was recorded in jBilling
            return "No TransactionJDO for " + pi.getId() + " – failure happened before jBilling recorded a payment ("
                    + message + ")";
        }
        TransactionJDO tx = jdo.get();
        tx.setResponseCode("FAILED");
        tx.setFailureCode(code);
        tx.setFailureMessage(message);
        tx.setLastModifiedDate(Instant.now());
        transactionJDORepository.save(tx);
        return "TransactionJDO → FAILED (" + code + "); "
                + notifier.notify(notification(AchEvent.PAYMENT_FAILED, eventId, pi, tx, code, message));
    }

    /** charge.dispute.created – for ACH: the customer told their bank the debit was unauthorized. */
    public String onDisputeCreated(String eventId, Dispute dispute) {
        String paymentIntentId = dispute.getPaymentIntent();
        if (paymentIntentId == null) {
            return "Dispute " + dispute.getId() + " has no payment intent";
        }
        Optional<TransactionJDO> jdo = transactionJDORepository.findFirstByTransactionId(paymentIntentId);
        PaymentIntent pi = retrievePaymentIntent(paymentIntentId, jdo.map(TransactionJDO::getBusinessId).orElse(null));
        return notifier.notify(notification(
                AchEvent.DISPUTE_CREATED, eventId, pi, jdo.orElse(null), dispute.getReason(),
                "Dispute " + dispute.getId() + " (" + dispute.getReason() + ", status " + dispute.getStatus() + ")"));
    }

    /** mandate.updated – status inactive means the customer revoked the authorization or the account is closed. */
    public String onMandateUpdated(String eventId, Mandate mandate) {
        if (!"inactive".equals(mandate.getStatus())) {
            return "Mandate " + mandate.getId() + " is " + mandate.getStatus() + " – nothing to do";
        }
        String businessId = firstAchBusinessId();
        PaymentMethod pm = stripeGateway.retrievePaymentMethod(
                mandate.getPaymentMethod(), requestOptionsProvider.getRequestOptions(businessId));
        Optional<StripePlatformCustomer> customer = Optional.ofNullable(pm.getCustomer())
                .flatMap(customerRepository::findByStripeCustomerId);
        var notification = new AchPaymentStatusNotification(
                AchEvent.MANDATE_INACTIVE,
                eventId,
                null,
                customer.map(StripePlatformCustomer::getMerchantId).orElse(null),
                customer.map(StripePlatformCustomer::getExternalId).orElse(null),
                null,
                null,
                pm.getType(),
                "mandate_inactive",
                "Mandate " + mandate.getId() + " for payment method " + pm.getId() + " is inactive",
                Instant.now());
        return notifier.notify(notification);
    }

    /** refund.updated / refund.failed – ACH refunds are asynchronous. */
    @Transactional
    public String onRefundUpdated(String eventId, Refund refund) {
        Optional<TransactionJDO> jdo = transactionJDORepository.findFirstByTransactionId(refund.getId());
        if (jdo.isEmpty()) {
            return "No TransactionJDO for refund " + refund.getId();
        }
        TransactionJDO tx = jdo.get();
        tx.setResponseStatus(RefundSubscriptionPaymentCommandHandler.refundStatus(refund.getStatus()));
        tx.setFailureCode(refund.getFailureReason());
        tx.setLastModifiedDate(Instant.now());
        transactionJDORepository.save(tx);
        String result = "Refund " + refund.getId() + " → " + tx.getResponseStatus();
        if ("failed".equals(refund.getStatus())) {
            var notification = new AchPaymentStatusNotification(
                    AchEvent.REFUND_FAILED, eventId, refund.getPaymentIntent(), accountPinFor(null, tx),
                    tx.getCustomerKey(), tx.getAmount(), tx.getCurrency(), tx.getPaymentMethodType(),
                    refund.getFailureReason(), "Refund " + refund.getId() + " failed: " + refund.getFailureReason(),
                    Instant.now());
            result += "; " + notifier.notify(notification);
        }
        return result;
    }

    // ------------------------------------------------------------------ helpers

    private AchPaymentStatusNotification notification(
            AchEvent event, String eventId, PaymentIntent pi, TransactionJDO tx, String code, String message) {
        String amount = tx != null && tx.getAmount() != null
                ? tx.getAmount()
                : pi != null && pi.getAmount() != null ? Money.fromMinorUnits(pi.getAmount()).toPlainString() : null;
        String currency = tx != null && tx.getCurrency() != null
                ? tx.getCurrency()
                : pi != null && pi.getCurrency() != null ? pi.getCurrency().toUpperCase(Locale.US) : null;
        String customerKey = tx != null && tx.getCustomerKey() != null
                ? tx.getCustomerKey()
                : metadata(pi).get("jBillingCustomerKey");
        String type = tx != null && tx.getPaymentMethodType() != null
                ? tx.getPaymentMethodType()
                : pi != null && pi.getPaymentMethodTypes() != null && pi.getPaymentMethodTypes().contains(US_BANK_ACCOUNT)
                        ? US_BANK_ACCOUNT
                        : null;
        return new AchPaymentStatusNotification(
                event, eventId, pi != null ? pi.getId() : null, accountPinFor(pi, tx), customerKey, amount, currency,
                type, code, message, Instant.now());
    }

    /**
     * Routing to the jBilling account (6.4 in the implementation plan): PI metadata accountPin, else the platform
     * customer's merchantId (= accountPin, set by PATCH /api/stripe/v4/platform/customers).
     */
    private String accountPinFor(PaymentIntent pi, TransactionJDO tx) {
        String fromMetadata = metadata(pi).get("accountPin");
        if (fromMetadata != null) {
            return fromMetadata;
        }
        Optional<StripePlatformCustomer> customer = Optional.empty();
        if (pi != null && pi.getCustomer() != null) {
            customer = customerRepository.findByStripeCustomerId(pi.getCustomer());
        }
        if (customer.isEmpty() && tx != null && tx.getCustomerKey() != null) {
            customer = customerRepository.findByExternalId(tx.getCustomerKey());
        }
        return customer.map(StripePlatformCustomer::getMerchantId).orElse(null);
    }

    private static Map<String, String> metadata(PaymentIntent pi) {
        return pi != null && pi.getMetadata() != null ? pi.getMetadata() : Map.of();
    }

    private PaymentIntent retrievePaymentIntent(String paymentIntentId, String businessId) {
        String effective = businessId != null ? businessId : firstAchBusinessId();
        return stripeGateway.retrievePaymentIntent(paymentIntentId, requestOptionsProvider.getRequestOptions(effective));
    }

    private String firstAchBusinessId() {
        return properties.ach().enabledBusinessIds().getFirst();
    }
}
