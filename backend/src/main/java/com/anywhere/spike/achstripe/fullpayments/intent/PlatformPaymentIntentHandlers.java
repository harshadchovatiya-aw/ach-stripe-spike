package com.anywhere.spike.achstripe.fullpayments.intent;

import com.anywhere.spike.achstripe.common.BadRequestException;
import com.anywhere.spike.achstripe.common.RequestOptionsProvider;
import com.anywhere.spike.achstripe.fullpayments.StripeGateway;
import com.anywhere.spike.achstripe.fullpayments.customer.StripePlatformCustomerRepository;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentIntentUpdateParams;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Copy of fullpayments CreatePlatformPaymentIntentHandler and UpdatePlatformPaymentIntentHandler
 * (POST/PATCH /api/stripe/v4/online-checkout/platform/payment-intents).
 */
@Component
public class PlatformPaymentIntentHandlers {

    private static final Logger log = LoggerFactory.getLogger(PlatformPaymentIntentHandlers.class);

    private final StripeGateway stripeGateway;
    private final RequestOptionsProvider requestOptionsProvider;
    private final PlatformIntentCache cache;
    private final StripePlatformCustomerRepository customerRepository;

    public PlatformPaymentIntentHandlers(
            StripeGateway stripeGateway,
            RequestOptionsProvider requestOptionsProvider,
            PlatformIntentCache cache,
            StripePlatformCustomerRepository customerRepository) {
        this.stripeGateway = stripeGateway;
        this.requestOptionsProvider = requestOptionsProvider;
        this.cache = cache;
        this.customerRepository = customerRepository;
    }

    /** Amount in minor units (cents), as in the fullpayments common Money model. */
    public record AmountMoney(Long amount, String currency) {}

    public enum FutureUsage {
        ON_SESSION,
        OFF_SESSION
        // ACH (P2): add NONE so a one-time bank payment does not save the payment method.
    }

    public record CreatePlatformPaymentIntentRequest(AmountMoney amountMoney, FutureUsage futureUsage) {}

    public record UpdatePlatformPaymentIntentRequest(AmountMoney amountMoney, FutureUsage futureUsage) {}

    public record CreatePlatformPaymentIntentResponse(String id, String clientSecret, String publicKey) {}

    private record CustomerResolution(String stripeCustomerId, String businessId) {}

    public CreatePlatformPaymentIntentResponse create(
            CreatePlatformPaymentIntentRequest request, String businessId, String customerKey) {
        validate(request.amountMoney());
        CustomerResolution resolution = resolveCustomer(customerKey, businessId);
        RequestOptions requestOptions = requestOptionsProvider.getRequestOptions(resolution.businessId());

        PaymentIntentCreateParams.Builder builder = PaymentIntentCreateParams.builder()
                .setAmount(request.amountMoney().amount())
                .setCurrency(request.amountMoney().currency().toLowerCase(Locale.US))
                // Cards are authorized here and captured later by the Braintree facade.
                // ACH (P2): us_bank_account does NOT support manual capture, so while this is MANUAL Stripe hides the
                // bank tab. For ACH set the top-level capture_method=AUTOMATIC and
                // payment_method_options.card.capture_method=MANUAL.
                .setCaptureMethod(PaymentIntentCreateParams.CaptureMethod.MANUAL);
        if (FutureUsage.ON_SESSION.equals(request.futureUsage())) {
            builder.setSetupFutureUsage(PaymentIntentCreateParams.SetupFutureUsage.ON_SESSION);
        } else {
            builder.setSetupFutureUsage(PaymentIntentCreateParams.SetupFutureUsage.OFF_SESSION);
        }
        if (resolution.stripeCustomerId() != null) {
            builder.setCustomer(resolution.stripeCustomerId());
        }
        PaymentIntent intent = stripeGateway.createPaymentIntent(builder.build(), requestOptions);

        cache.putPaymentIntent(
                new PlatformIntentCache.PlatformPaymentIntentMetadata(intent.getId(), resolution.businessId()));
        log.info("Saved platform payment intent id: {} for business id: {}", intent.getId(), resolution.businessId());
        return new CreatePlatformPaymentIntentResponse(
                intent.getId(), intent.getClientSecret(), requestOptionsProvider.getPublicKey(resolution.businessId()));
    }

    public void update(UpdatePlatformPaymentIntentRequest request, String businessId, String paymentIntentId) {
        validate(request.amountMoney());
        RequestOptions requestOptions = requestOptionsProvider.getRequestOptions(businessId);
        PaymentIntent paymentIntent = stripeGateway.retrievePaymentIntent(paymentIntentId, requestOptions);
        PaymentIntentUpdateParams.Builder builder = PaymentIntentUpdateParams.builder()
                .setAmount(request.amountMoney().amount())
                .setCurrency(request.amountMoney().currency().toLowerCase(Locale.US));
        if (request.futureUsage() != null) {
            switch (request.futureUsage()) {
                case OFF_SESSION -> builder.setSetupFutureUsage(PaymentIntentUpdateParams.SetupFutureUsage.OFF_SESSION);
                case ON_SESSION -> builder.setSetupFutureUsage(PaymentIntentUpdateParams.SetupFutureUsage.ON_SESSION);
            }
        }
        stripeGateway.updatePaymentIntent(paymentIntent, builder.build(), requestOptions);
        log.info("Updated platform payment intent id: {} for business id: {}", paymentIntentId, businessId);
    }

    private CustomerResolution resolveCustomer(String customerKey, String fallbackBusinessId) {
        if (customerKey == null || customerKey.isBlank()) {
            return new CustomerResolution(null, fallbackBusinessId);
        }
        // Note: the real handler does NOT create the customer here (only the setup-intent endpoint does).
        // ACH (P2): create it up front so the bank account and mandate belong to the customer at confirmation.
        return customerRepository
                .findByExternalId(customerKey)
                .map(c -> new CustomerResolution(c.getStripeCustomerId(), c.getBusinessId()))
                .orElse(new CustomerResolution(null, fallbackBusinessId));
    }

    private static void validate(AmountMoney amountMoney) {
        if (amountMoney == null || amountMoney.amount() == null || amountMoney.amount() <= 0
                || amountMoney.currency() == null) {
            throw new BadRequestException("amountMoney.amount (minor units) and amountMoney.currency are required");
        }
    }
}
