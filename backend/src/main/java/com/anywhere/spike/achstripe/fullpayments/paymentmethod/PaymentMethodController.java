package com.anywhere.spike.achstripe.fullpayments.paymentmethod;

import com.anywhere.spike.achstripe.common.RequestOptionsProvider;
import com.anywhere.spike.achstripe.fullpayments.StripeGateway;
import com.anywhere.spike.achstripe.fullpayments.customer.StripePlatformCustomerRepository;
import com.stripe.model.PaymentMethod;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * FullPayments GET /api/stripe/v4/platform/payment-methods/{paymentMethodId} (GetPaymentMethodByIdQueryHandler).
 * Used by the jBilling hosted API (PaymentHelper.getPaymentMethodDetails) after the UI confirms a SetupIntent.
 */
@RestController
public class PaymentMethodController {

    private final StripeGateway stripeGateway;
    private final RequestOptionsProvider requestOptionsProvider;
    private final StripePlatformCustomerRepository customerRepository;

    public PaymentMethodController(
            StripeGateway stripeGateway,
            RequestOptionsProvider requestOptionsProvider,
            StripePlatformCustomerRepository customerRepository) {
        this.stripeGateway = stripeGateway;
        this.requestOptionsProvider = requestOptionsProvider;
        this.customerRepository = customerRepository;
    }

    public record CardDetails(
            String brand, List<String> networks, String preferredNetwork, String last4, Integer expMonth, Integer expYear) {}

    /** ACH (P3): add bankAccountDetails (bankName, last4, routingNumber, accountType, verification status). */
    public record PaymentMethodDetailsResponse(String paymentMethodId, String type, CardDetails cardDetails) {}

    @GetMapping("/api/stripe/v4/platform/payment-methods/{paymentMethodId}")
    public PaymentMethodDetailsResponse getPaymentMethodById(
            @RequestHeader("X-Business-ID") String businessId,
            @PathVariable String paymentMethodId,
            @RequestParam(required = false) String externalId) {
        String effectiveBusinessId = (externalId == null || externalId.isBlank())
                ? businessId
                : customerRepository.findByExternalId(externalId).map(c -> c.getBusinessId()).orElse(businessId);
        PaymentMethod paymentMethod = stripeGateway.retrievePaymentMethod(
                paymentMethodId, requestOptionsProvider.getRequestOptions(effectiveBusinessId));

        CardDetails cardDetails = null;
        if (paymentMethod.getCard() != null) {
            PaymentMethod.Card card = paymentMethod.getCard();
            var networks = card.getNetworks();
            cardDetails = new CardDetails(
                    card.getBrand(),
                    networks != null && networks.getAvailable() != null ? networks.getAvailable() : List.of(),
                    networks != null ? networks.getPreferred() : null,
                    card.getLast4(),
                    card.getExpMonth() != null ? card.getExpMonth().intValue() : null,
                    card.getExpYear() != null ? card.getExpYear().intValue() : null);
        }
        return new PaymentMethodDetailsResponse(paymentMethod.getId(), paymentMethod.getType(), cardDetails);
    }
}
