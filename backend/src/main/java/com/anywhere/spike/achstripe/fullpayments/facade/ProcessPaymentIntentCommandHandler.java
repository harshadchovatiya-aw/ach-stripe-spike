package com.anywhere.spike.achstripe.fullpayments.facade;

import com.anywhere.spike.achstripe.common.RequestOptionsProvider;
import com.anywhere.spike.achstripe.fullpayments.StripeGateway;
import com.anywhere.spike.achstripe.fullpayments.customer.StripePlatformCustomerService;
import com.anywhere.spike.achstripe.fullpayments.intent.PlatformIntentCache;
import com.stripe.model.Customer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentMethod;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentUpdateParams;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Copy of fullpayments ProcessPaymentIntentCommandHandler: "vault with payment intent" – used when the UI confirmed a
 * platform PaymentIntent and jBilling calls /api/braintree/createVaultWithNonce with nonce = pi_...
 * The PI is NOT captured here; it is parked in pendingCapturePaymentIntentCache and captured by the next
 * /api/braintree/processPayment call for the same customerKey.
 */
@Component
public class ProcessPaymentIntentCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(ProcessPaymentIntentCommandHandler.class);

    private final StripeGateway stripeGateway;
    private final RequestOptionsProvider requestOptionsProvider;
    private final PlatformIntentCache cache;
    private final StripePlatformCustomerService platformCustomerService;
    private final VaultingHelper vaultingHelper;

    public ProcessPaymentIntentCommandHandler(
            StripeGateway stripeGateway,
            RequestOptionsProvider requestOptionsProvider,
            PlatformIntentCache cache,
            StripePlatformCustomerService platformCustomerService,
            VaultingHelper vaultingHelper) {
        this.stripeGateway = stripeGateway;
        this.requestOptionsProvider = requestOptionsProvider;
        this.cache = cache;
        this.platformCustomerService = platformCustomerService;
        this.vaultingHelper = vaultingHelper;
    }

    @Transactional
    public VaultingHelper.VaultingResult vaultWithPaymentIntent(
            PlatformIntentCache.PlatformPaymentIntentMetadata metadata,
            String jBillingCustomerKey,
            String productKey,
            StripePlatformCustomerService.BillingDetails billingDetails) {
        log.info("Vaulting with payment intent {} for customerKey {}", metadata.paymentIntentId(), jBillingCustomerKey);
        String businessId = metadata.businessId();
        RequestOptions requestOptions = requestOptionsProvider.getRequestOptions(businessId);
        PaymentIntent paymentIntent =
                stripeGateway.retrieveWithExpandedPaymentMethod(metadata.paymentIntentId(), requestOptions);
        PaymentMethod paymentMethod = paymentIntent.getPaymentMethodObject();

        List<PaymentMethod> vaultedPaymentMethods = Collections.emptyList();
        if ("off_session".equals(paymentIntent.getSetupFutureUsage())) {
            vaultedPaymentMethods = List.of(paymentMethod);
        }
        Customer stripeCustomer = platformCustomerService.fetchOrCreatePlatformStripeCustomer(
                jBillingCustomerKey, businessId, billingDetails, vaultedPaymentMethods, requestOptions);
        attachCustomerIfAbsent(paymentIntent, stripeCustomer, requestOptions);
        vaultingHelper.saveTransactionJdoForBackwardCompatibility(
                stripeCustomer, jBillingCustomerKey, productKey, businessId);
        cache.putPendingCapture(
                jBillingCustomerKey,
                new PlatformIntentCache.PendingCapturePaymentIntentMetadata(metadata.paymentIntentId(), businessId));
        return vaultingHelper.buildVaultingResult(paymentMethod, billingDetails, jBillingCustomerKey);
    }

    private void attachCustomerIfAbsent(PaymentIntent paymentIntent, Customer customer, RequestOptions options) {
        if (paymentIntent.getCustomer() != null) {
            return;
        }
        stripeGateway.updatePaymentIntent(
                paymentIntent,
                PaymentIntentUpdateParams.builder().setCustomer(customer.getId()).build(),
                options);
    }
}
