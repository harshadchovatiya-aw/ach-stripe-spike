package com.anywhere.spike.achstripe.fullpayments.facade;

import com.anywhere.spike.achstripe.common.Money;
import com.anywhere.spike.achstripe.common.RequestOptionsProvider;
import com.anywhere.spike.achstripe.fullpayments.StripeGateway;
import com.anywhere.spike.achstripe.fullpayments.customer.StripePlatformCustomer;
import com.anywhere.spike.achstripe.fullpayments.customer.StripePlatformCustomerRepository;
import com.anywhere.spike.achstripe.fullpayments.intent.PlatformIntentCache;
import com.stripe.model.Customer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentMethod;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Copy of fullpayments ProcessPaymentCommandHandler – used by /api/braintree/processPayment and
 * /createTransactionWithCustomerKey (jBilling charging a saved instrument / recurring billing).
 * Either captures a pending signup PI, or creates a new off-session PI with the saved payment method.
 */
@Component
public class ProcessPaymentCommandHandler {

    static final String SUBSCRIPTION_CURRENCY = "USD";
    private static final Logger log = LoggerFactory.getLogger(ProcessPaymentCommandHandler.class);

    private final StripeGateway stripeGateway;
    private final RequestOptionsProvider requestOptionsProvider;
    private final PlatformIntentCache cache;
    private final TransactionJDORepository transactionJDORepository;
    private final CapturePaymentIntentCommandHandler captureHandler;
    private final StripePlatformCustomerRepository customerRepository;

    public ProcessPaymentCommandHandler(
            StripeGateway stripeGateway,
            RequestOptionsProvider requestOptionsProvider,
            PlatformIntentCache cache,
            TransactionJDORepository transactionJDORepository,
            CapturePaymentIntentCommandHandler captureHandler,
            StripePlatformCustomerRepository customerRepository) {
        this.stripeGateway = stripeGateway;
        this.requestOptionsProvider = requestOptionsProvider;
        this.cache = cache;
        this.transactionJDORepository = transactionJDORepository;
        this.captureHandler = captureHandler;
        this.customerRepository = customerRepository;
    }

    public TransactionJDO processPayment(StripePlatformCustomer customer, String amount, String productKey) {
        log.info("Processing payment for Stripe customer: {} for amount: {}", customer.getExternalId(), amount);
        Optional<PlatformIntentCache.PendingCapturePaymentIntentMetadata> pendingCapture =
                cache.getPendingCapture(customer.getExternalId());
        PaymentIntent paymentIntent;
        if (pendingCapture.isPresent()) {
            RequestOptions options = requestOptionsProvider.getRequestOptions(pendingCapture.get().businessId());
            paymentIntent = captureHandler.capturePaymentIntent(pendingCapture.get().paymentIntentId(), amount, options);
            cache.evictPendingCapture(customer.getExternalId());
        } else {
            paymentIntent = createNewPaymentWithVaultedPaymentMethod(customer, amount);
        }
        log.info("Processed payment with id {} status {}", paymentIntent.getId(), paymentIntent.getStatus());
        return saveTransactionJDO(customer, amount, productKey, paymentIntent);
    }

    private PaymentIntent createNewPaymentWithVaultedPaymentMethod(StripePlatformCustomer customer, String amount) {
        RequestOptions options = requestOptionsProvider.getRequestOptions(customer.getBusinessId());
        String paymentMethodId = getPaymentMethodId(customer, options);
        // ACH (P5): for a us_bank_account PM also pass the mandate and payment_method_types=[us_bank_account];
        // the PI comes back "processing", which must be recorded as PENDING, not COMPLETED.
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setCurrency(SUBSCRIPTION_CURRENCY.toLowerCase(Locale.US))
                .setAmount(Money.toMinorUnits(Money.parse(amount)))
                .setCustomer(customer.getStripeCustomerId())
                .setPaymentMethod(paymentMethodId)
                .setConfirm(true)
                .setOffSession(true)
                .build();
        return stripeGateway.createPaymentIntent(params, options);
    }

    private String getPaymentMethodId(StripePlatformCustomer customer, RequestOptions options) {
        if (!customer.getPaymentMethodIds().isEmpty()) {
            return customer.getPaymentMethodIds().getFirst();
        }
        Customer stripeCustomer = stripeGateway
                .retrieveCustomer(customer.getStripeCustomerId(), options)
                .orElseThrow(() -> new IllegalStateException(
                        "Can't find stripe customer with id: " + customer.getStripeCustomerId()));
        List<PaymentMethod> paymentMethods = stripeGateway.listCustomerPaymentMethods(stripeCustomer, options);
        if (paymentMethods.isEmpty()) {
            throw new IllegalStateException("No payment methods found for customer: " + stripeCustomer.getId());
        }
        String paymentMethodId = paymentMethods.getFirst().getId();
        // AsyncPaymentMethodUpdater.savePaymentMethodId in fullpayments
        customer.addPaymentMethodId(paymentMethodId);
        customerRepository.save(customer);
        return paymentMethodId;
    }

    private TransactionJDO saveTransactionJDO(
            StripePlatformCustomer customer, String amount, String productKey, PaymentIntent paymentIntent) {
        TransactionJDO jdo = new TransactionJDO();
        jdo.setProfileId(customer.getStripeCustomerId());
        jdo.setSourceType("braintree");
        jdo.setProductKey(productKey != null ? productKey : customer.getStripeCustomerId());
        jdo.setBusinessId(customer.getBusinessId());
        jdo.setTransactionId(paymentIntent.getId());
        jdo.setAmount(amount);
        jdo.setCustomerKey(customer.getExternalId());
        jdo.setPaymentDate(String.valueOf(paymentIntent.getCreated()));
        jdo.setLastModifiedDate(Instant.now());
        jdo.setResponseCode("COMPLETED");
        jdo.setCurrency(paymentIntent.getCurrency().toUpperCase(Locale.US));
        return transactionJDORepository.save(jdo);
    }
}
