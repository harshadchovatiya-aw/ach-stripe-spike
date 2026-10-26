package com.anywhere.spike.achstripe.fullpayments.facade;

import com.anywhere.spike.achstripe.common.RequestOptionsProvider;
import com.anywhere.spike.achstripe.fullpayments.StripeGateway;
import com.anywhere.spike.achstripe.fullpayments.customer.StripePlatformCustomerService;
import com.anywhere.spike.achstripe.fullpayments.intent.PlatformIntentCache;
import com.stripe.model.Customer;
import com.stripe.model.PaymentMethod;
import com.stripe.model.SetupIntent;
import com.stripe.net.RequestOptions;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Copy of fullpayments ProcessSetupIntentCommandHandler: called via /api/braintree/createVaultWithNonce with
 * nonce = seti_... (only for setup intents created WITHOUT a customerKey). Creates the Stripe platform customer
 * with the confirmed payment method attached.
 */
@Component
public class ProcessSetupIntentCommandHandler {

    private final StripeGateway stripeGateway;
    private final RequestOptionsProvider requestOptionsProvider;
    private final StripePlatformCustomerService platformCustomerService;
    private final VaultingHelper vaultingHelper;

    public ProcessSetupIntentCommandHandler(
            StripeGateway stripeGateway,
            RequestOptionsProvider requestOptionsProvider,
            StripePlatformCustomerService platformCustomerService,
            VaultingHelper vaultingHelper) {
        this.stripeGateway = stripeGateway;
        this.requestOptionsProvider = requestOptionsProvider;
        this.platformCustomerService = platformCustomerService;
        this.vaultingHelper = vaultingHelper;
    }

    @Transactional
    public VaultingHelper.VaultingResult processSetupIntent(
            PlatformIntentCache.PlatformSetupIntentMetadata metadata,
            String jBillingCustomerKey,
            String productKey,
            StripePlatformCustomerService.BillingDetails billingDetails) {
        String businessId = metadata.businessId();
        RequestOptions requestOptions = requestOptionsProvider.getRequestOptions(businessId);
        SetupIntent setupIntent =
                stripeGateway.retrieveSetupIntentWithExpandedPaymentMethod(metadata.setupIntentId(), requestOptions);
        PaymentMethod paymentMethod = setupIntent.getPaymentMethodObject();

        Customer stripeCustomer = platformCustomerService.createStripeCustomerForPlatformAccount(
                jBillingCustomerKey, businessId, billingDetails, List.of(paymentMethod), requestOptions);
        vaultingHelper.saveTransactionJdoForBackwardCompatibility(
                stripeCustomer, jBillingCustomerKey, productKey, businessId);
        return vaultingHelper.buildVaultingResult(paymentMethod, billingDetails, jBillingCustomerKey);
    }
}
