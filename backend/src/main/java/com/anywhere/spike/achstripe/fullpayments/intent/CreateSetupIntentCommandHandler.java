package com.anywhere.spike.achstripe.fullpayments.intent;

import com.anywhere.spike.achstripe.common.RequestOptionsProvider;
import com.anywhere.spike.achstripe.fullpayments.StripeGateway;
import com.anywhere.spike.achstripe.fullpayments.customer.StripePlatformCustomer;
import com.anywhere.spike.achstripe.fullpayments.customer.StripePlatformCustomerRepository;
import com.stripe.model.Customer;
import com.stripe.model.SetupIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.SetupIntentCreateParams;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Copy of fullpayments com.acti.payment.stripe.setup.CreateSetupIntentCommandHandler
 * (POST /api/stripe/v4/platform/setup-intents).
 */
@Component
public class CreateSetupIntentCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(CreateSetupIntentCommandHandler.class);

    private final StripeGateway stripeGateway;
    private final StripePlatformCustomerRepository customerRepository;
    private final RequestOptionsProvider requestOptionsProvider;
    private final PlatformIntentCache cache;

    public CreateSetupIntentCommandHandler(
            StripeGateway stripeGateway,
            StripePlatformCustomerRepository customerRepository,
            RequestOptionsProvider requestOptionsProvider,
            PlatformIntentCache cache) {
        this.stripeGateway = stripeGateway;
        this.customerRepository = customerRepository;
        this.requestOptionsProvider = requestOptionsProvider;
        this.cache = cache;
    }

    public record CreateSetupIntentResponse(String id, String clientSecret, String publicKey) {}

    private record CustomerResolution(String stripeCustomerId, String businessId) {}

    @Transactional
    public CreateSetupIntentResponse handle(String businessId, String customerKey) {
        boolean hasCustomerKey = customerKey != null && !customerKey.isBlank();
        CustomerResolution resolution = resolveCustomer(businessId, customerKey, hasCustomerKey);
        RequestOptions requestOptions = requestOptionsProvider.getRequestOptions(resolution.businessId());

        SetupIntentCreateParams.Builder builder = SetupIntentCreateParams.builder()
                .setUsage(SetupIntentCreateParams.Usage.OFF_SESSION)
                // Payment methods come from the Stripe Dashboard ("dynamic payment methods").
                // ACH (P1): once ACH Direct Debit is enabled on the AnswerForce account it shows up here, add
                // payment_method_options.us_bank_account (verification_method, financial_connections.permissions).
                .setAutomaticPaymentMethods(SetupIntentCreateParams.AutomaticPaymentMethods.builder()
                        .setEnabled(true)
                        .build());
        if (resolution.stripeCustomerId() != null) {
            builder.setCustomer(resolution.stripeCustomerId());
        }
        SetupIntent setupIntent = stripeGateway.createSetupIntent(builder.build(), requestOptions);

        if (!hasCustomerKey) {
            // signup flow: the SetupIntent id is later sent as the "nonce" to /api/braintree/createVaultWithNonce
            cache.putSetupIntent(
                    new PlatformIntentCache.PlatformSetupIntentMetadata(setupIntent.getId(), resolution.businessId()));
        }
        return new CreateSetupIntentResponse(
                setupIntent.getId(),
                setupIntent.getClientSecret(),
                requestOptionsProvider.getPublicKey(resolution.businessId()));
    }

    private CustomerResolution resolveCustomer(String businessId, String customerKey, boolean hasCustomerKey) {
        if (!hasCustomerKey) {
            return new CustomerResolution(null, businessId);
        }
        return customerRepository
                .findByExternalId(customerKey)
                .map(c -> new CustomerResolution(c.getStripeCustomerId(), c.getBusinessId()))
                .orElseGet(() -> {
                    RequestOptions options = requestOptionsProvider.getRequestOptions(businessId);
                    Customer customer = stripeGateway.createCustomer(
                            CustomerCreateParams.builder()
                                    .putMetadata("customerKey", customerKey)
                                    .build(),
                            options);
                    log.info("Created new Stripe customer {} for customerKey {}", customer.getId(), customerKey);
                    customerRepository.save(StripePlatformCustomer.of(customerKey, customer.getId(), businessId, List.of()));
                    return new CustomerResolution(customer.getId(), businessId);
                });
    }
}
