package com.anywhere.spike.achstripe.fullpayments.webhook;

import com.anywhere.spike.achstripe.common.RequestOptionsProvider;
import com.anywhere.spike.achstripe.fullpayments.StripeGateway;
import com.anywhere.spike.achstripe.fullpayments.customer.StripePlatformCustomer;
import com.anywhere.spike.achstripe.fullpayments.customer.StripePlatformCustomerRepository;
import com.stripe.model.PaymentMethod;
import com.stripe.net.RequestOptions;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Copy of fullpayments UpdatePlatformCustomerPaymentMethodOnPaymentMethodAttachedHandler.
 * Keeps exactly ONE payment method per platform customer: a newly attached PM detaches all the others.
 * (So a customer has a card OR – later – a bank account, never both.)
 */
@Component
public class UpdatePlatformCustomerPaymentMethodOnPaymentMethodAttachedHandler {

    private static final Logger log =
            LoggerFactory.getLogger(UpdatePlatformCustomerPaymentMethodOnPaymentMethodAttachedHandler.class);

    private final StripePlatformCustomerRepository repository;
    private final StripeGateway stripeGateway;
    private final RequestOptionsProvider requestOptionsProvider;

    public UpdatePlatformCustomerPaymentMethodOnPaymentMethodAttachedHandler(
            StripePlatformCustomerRepository repository,
            StripeGateway stripeGateway,
            RequestOptionsProvider requestOptionsProvider) {
        this.repository = repository;
        this.stripeGateway = stripeGateway;
        this.requestOptionsProvider = requestOptionsProvider;
    }

    @Transactional
    public String handle(PaymentMethod paymentMethod) {
        String stripeCustomerId = paymentMethod.getCustomer();
        if (stripeCustomerId == null) {
            return "PaymentMethod not attached to a customer – skipped";
        }
        return repository
                .findByStripeCustomerId(stripeCustomerId)
                .map(customer -> updatePaymentMethod(customer, paymentMethod))
                .orElse("No platform customer for " + stripeCustomerId + " – skipped");
    }

    private String updatePaymentMethod(StripePlatformCustomer customer, PaymentMethod newPaymentMethod) {
        String newId = newPaymentMethod.getId();
        if (customer.getPaymentMethodIds().contains(newId)) {
            return "PaymentMethod " + newId + " already known – skipped";
        }
        RequestOptions options = requestOptionsProvider.getRequestOptions(customer.getBusinessId());
        List<String> existing = new ArrayList<>(customer.getPaymentMethodIds());
        for (String existingId : existing) {
            try {
                stripeGateway.detachPaymentMethod(existingId, options);
            } catch (Exception e) {
                log.warn("Failed to detach payment method {}: {}", existingId, e.getMessage());
            }
            customer.removePaymentMethodId(existingId);
        }
        customer.addPaymentMethodId(newId);
        repository.save(customer);
        return "Platform customer " + customer.getExternalId() + " now uses " + newId + ", detached " + existing;
    }
}
