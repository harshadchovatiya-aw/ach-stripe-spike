package com.anywhere.spike.achstripe.fullpayments.customer;

import com.anywhere.spike.achstripe.common.BadRequestException;
import com.anywhere.spike.achstripe.common.RequestOptionsProvider;
import com.anywhere.spike.achstripe.fullpayments.StripeGateway;
import com.stripe.model.Customer;
import com.stripe.model.PaymentMethod;
import com.stripe.net.RequestOptions;
import com.stripe.param.CustomerCreateParams;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Copy of fullpayments StripePlatformCustomerService. */
@Service
public class StripePlatformCustomerService {

    private static final Logger log = LoggerFactory.getLogger(StripePlatformCustomerService.class);

    private final StripeGateway stripeGateway;
    private final StripePlatformCustomerRepository repository;
    private final RequestOptionsProvider requestOptionsProvider;
    /** Stand-in for the createPlatformCustomerLock (a distributed lock in fullpayments). */
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public StripePlatformCustomerService(
            StripeGateway stripeGateway,
            StripePlatformCustomerRepository repository,
            RequestOptionsProvider requestOptionsProvider) {
        this.stripeGateway = stripeGateway;
        this.repository = repository;
        this.requestOptionsProvider = requestOptionsProvider;
    }

    public record BillingDetails(String firstName, String lastName, String country, String postalCode, String email) {}

    @Transactional
    public Customer createStripeCustomerForPlatformAccount(
            String jBillingCustomerKey,
            String businessId,
            BillingDetails billingDetails,
            List<PaymentMethod> paymentMethods,
            RequestOptions requestOptions) {
        synchronized (locks.computeIfAbsent(jBillingCustomerKey, k -> new Object())) {
            CustomerCreateParams.Builder builder = builder(billingDetails);
            builder.putMetadata("jBillingCustomerKey", jBillingCustomerKey);
            Customer customer = stripeGateway.createCustomer(builder.build(), requestOptions);
            for (PaymentMethod paymentMethod : paymentMethods) {
                attachIfNeeded(paymentMethod, customer.getId(), requestOptions);
            }
            repository.save(StripePlatformCustomer.of(
                    jBillingCustomerKey,
                    customer.getId(),
                    businessId,
                    paymentMethods.stream().map(PaymentMethod::getId).toList()));
            log.info("Created Stripe platform customer {} for customerKey {}", customer.getId(), jBillingCustomerKey);
            return customer;
        }
    }

    @Transactional
    public Customer fetchOrCreatePlatformStripeCustomer(
            String jBillingCustomerKey,
            String businessId,
            BillingDetails billingDetails,
            List<PaymentMethod> paymentMethods,
            RequestOptions requestOptions) {
        return repository
                .findByExternalId(jBillingCustomerKey)
                .flatMap(platformCustomer -> fetchAndSyncPaymentMethods(platformCustomer, paymentMethods, requestOptions))
                .orElseGet(() -> createStripeCustomerForPlatformAccount(
                        jBillingCustomerKey, businessId, billingDetails, paymentMethods, requestOptions));
    }

    @Transactional
    public void detachAllPaymentMethods(StripePlatformCustomer customer) {
        RequestOptions requestOptions = requestOptionsProvider.getRequestOptions(customer.getBusinessId());
        for (String paymentMethodId : new ArrayList<>(customer.getPaymentMethodIds())) {
            stripeGateway.detachPaymentMethod(paymentMethodId, requestOptions);
            customer.removePaymentMethodId(paymentMethodId);
        }
        repository.save(customer);
    }

    /** PATCH /api/stripe/v4/platform/customers?externalId= {merchantId} (updateCustomerKeyInFullPayments). */
    @Transactional
    public void updatePlatformCustomer(String externalId, String merchantId) {
        StripePlatformCustomer customer = repository
                .findByExternalId(externalId)
                .orElseThrow(() ->
                        new BadRequestException("Couldn't find stripe platform customer by externalId: " + externalId));
        customer.setMerchantId(merchantId);
        repository.save(customer);
    }

    private Optional<Customer> fetchAndSyncPaymentMethods(
            StripePlatformCustomer platformCustomer, List<PaymentMethod> paymentMethods, RequestOptions options) {
        Optional<Customer> retrieved = stripeGateway.retrieveCustomer(platformCustomer.getStripeCustomerId(), options);
        if (retrieved.isEmpty()) {
            log.info("Platform customer missing in Stripe, deleting from DB, customerKey: {}",
                    platformCustomer.getExternalId());
            repository.delete(platformCustomer);
            return Optional.empty();
        }
        Customer customer = retrieved.get();
        List<PaymentMethod> newPaymentMethods = paymentMethods.stream()
                .filter(pm -> !platformCustomer.getPaymentMethodIds().contains(pm.getId()))
                .toList();
        for (PaymentMethod paymentMethod : newPaymentMethods) {
            attachIfNeeded(paymentMethod, customer.getId(), options);
            platformCustomer.addPaymentMethodId(paymentMethod.getId());
        }
        if (!newPaymentMethods.isEmpty()) {
            repository.save(platformCustomer);
        }
        return Optional.of(customer);
    }

    /**
     * Spike addition: when the PI/SI was created with the customer (always the case for ACH, P2), Stripe has already
     * attached the payment method on confirmation, so attaching again is skipped.
     */
    private void attachIfNeeded(PaymentMethod paymentMethod, String customerId, RequestOptions options) {
        if (customerId.equals(paymentMethod.getCustomer())) {
            log.debug("PaymentMethod {} already attached to {}", paymentMethod.getId(), customerId);
            return;
        }
        stripeGateway.attachPaymentMethod(paymentMethod, customerId, options);
    }

    private static CustomerCreateParams.Builder builder(BillingDetails billingDetails) {
        CustomerCreateParams.Builder builder = CustomerCreateParams.builder();
        if (billingDetails != null) {
            CustomerCreateParams.Address.Builder address = CustomerCreateParams.Address.builder();
            if (billingDetails.country() != null) {
                address.setCountry(billingDetails.country());
            }
            if (billingDetails.postalCode() != null) {
                address.setPostalCode(billingDetails.postalCode());
            }
            builder.setAddress(address.build());
            if (billingDetails.firstName() != null) {
                builder.setName(billingDetails.firstName() + " " + billingDetails.lastName());
            }
            if (billingDetails.email() != null) {
                builder.setEmail(billingDetails.email());
            }
        }
        return builder;
    }
}
