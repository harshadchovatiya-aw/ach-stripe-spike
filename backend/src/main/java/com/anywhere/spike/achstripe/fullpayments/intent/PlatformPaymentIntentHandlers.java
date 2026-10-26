package com.anywhere.spike.achstripe.fullpayments.intent;

import com.anywhere.spike.achstripe.common.BadRequestException;
import com.anywhere.spike.achstripe.common.RequestOptionsProvider;
import com.anywhere.spike.achstripe.fullpayments.StripeGateway;
import com.anywhere.spike.achstripe.fullpayments.customer.StripePlatformCustomer;
import com.anywhere.spike.achstripe.fullpayments.customer.StripePlatformCustomerRepository;
import com.stripe.model.Customer;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentIntentUpdateParams;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Copy of fullpayments CreatePlatformPaymentIntentHandler and UpdatePlatformPaymentIntentHandler
 * (POST/PATCH /api/stripe/v4/online-checkout/platform/payment-intents), extended for ACH (P2).
 */
@Component
public class PlatformPaymentIntentHandlers {

    private static final Logger log = LoggerFactory.getLogger(PlatformPaymentIntentHandlers.class);

    private final StripeGateway stripeGateway;
    private final RequestOptionsProvider requestOptionsProvider;
    private final PlatformIntentCache cache;
    private final StripePlatformCustomerRepository customerRepository;
    private final AchIntentOptions achIntentOptions;

    public PlatformPaymentIntentHandlers(
            StripeGateway stripeGateway,
            RequestOptionsProvider requestOptionsProvider,
            PlatformIntentCache cache,
            StripePlatformCustomerRepository customerRepository,
            AchIntentOptions achIntentOptions) {
        this.stripeGateway = stripeGateway;
        this.requestOptionsProvider = requestOptionsProvider;
        this.cache = cache;
        this.customerRepository = customerRepository;
        this.achIntentOptions = achIntentOptions;
    }

    /** Amount in minor units (cents), as in the fullpayments common Money model. */
    public record AmountMoney(Long amount, String currency) {}

    public enum FutureUsage {
        ON_SESSION,
        OFF_SESSION,
        /** ACH (P2): no setup_future_usage – a true one-time payment, nothing is saved. */
        NONE
    }

    /**
     * metadata (ACH, P2): accountPin / brandId / jbillingUserId so that later webhooks (payment_failed, disputes)
     * can be routed to the right jBilling account.
     */
    public record CreatePlatformPaymentIntentRequest(
            AmountMoney amountMoney, FutureUsage futureUsage, Map<String, String> metadata) {}

    public record UpdatePlatformPaymentIntentRequest(AmountMoney amountMoney, FutureUsage futureUsage) {}

    /** achEnabled is spike-only (tells the UI whether a bank tab is expected). */
    public record CreatePlatformPaymentIntentResponse(String id, String clientSecret, String publicKey, boolean achEnabled) {}

    private record CustomerResolution(String stripeCustomerId, String businessId) {}

    @Transactional
    public CreatePlatformPaymentIntentResponse create(
            CreatePlatformPaymentIntentRequest request, String businessId, String customerKey) {
        validate(request.amountMoney());
        boolean achEnabled = achIntentOptions.isEnabled(businessId);
        CustomerResolution resolution = resolveCustomer(customerKey, businessId, achEnabled);
        RequestOptions requestOptions = requestOptionsProvider.getRequestOptions(resolution.businessId());

        PaymentIntentCreateParams.Builder builder = PaymentIntentCreateParams.builder()
                .setAmount(request.amountMoney().amount())
                .setCurrency(request.amountMoney().currency().toLowerCase(Locale.US));
        if (achEnabled) {
            // ACH (P2): us_bank_account does NOT support manual capture (Stripe hides it while capture_method=manual).
            // Top-level automatic capture + payment_method_options[card][capture_method]=manual keeps cards on
            // auth-then-capture.
            builder.setCaptureMethod(PaymentIntentCreateParams.CaptureMethod.AUTOMATIC);
            achIntentOptions.apply(builder);
        } else {
            // Unchanged behaviour for every other business id (Setmore, ServiceForge): authorize now, capture later.
            builder.setCaptureMethod(PaymentIntentCreateParams.CaptureMethod.MANUAL);
        }

        FutureUsage futureUsage = request.futureUsage() == null ? FutureUsage.OFF_SESSION : request.futureUsage();
        switch (futureUsage) {
            case ON_SESSION -> builder.setSetupFutureUsage(PaymentIntentCreateParams.SetupFutureUsage.ON_SESSION);
            case OFF_SESSION -> builder.setSetupFutureUsage(PaymentIntentCreateParams.SetupFutureUsage.OFF_SESSION);
            case NONE -> { /* ACH (P2): one-time – no setup_future_usage */ }
        }
        if (resolution.stripeCustomerId() != null) {
            builder.setCustomer(resolution.stripeCustomerId());
        }
        if (request.metadata() != null) {
            request.metadata().forEach((k, v) -> {
                if (v != null) {
                    builder.putMetadata(k, v);
                }
            });
        }
        if (customerKey != null && !customerKey.isBlank()) {
            builder.putMetadata("jBillingCustomerKey", customerKey);
        }
        PaymentIntent intent = stripeGateway.createPaymentIntent(builder.build(), requestOptions);

        cache.putPaymentIntent(
                new PlatformIntentCache.PlatformPaymentIntentMetadata(intent.getId(), resolution.businessId()));
        log.info("Saved platform payment intent id: {} for business id: {} (ACH enabled: {})",
                intent.getId(), resolution.businessId(), achEnabled);
        return new CreatePlatformPaymentIntentResponse(
                intent.getId(),
                intent.getClientSecret(),
                requestOptionsProvider.getPublicKey(resolution.businessId()),
                achEnabled);
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
                case NONE -> builder.putExtraParam("setup_future_usage", ""); // "" unsets the field
            }
        }
        stripeGateway.updatePaymentIntent(paymentIntent, builder.build(), requestOptions);
        log.info("Updated platform payment intent id: {} for business id: {}", paymentIntentId, businessId);
    }

    private CustomerResolution resolveCustomer(String customerKey, String fallbackBusinessId, boolean achEnabled) {
        if (customerKey == null || customerKey.isBlank()) {
            return new CustomerResolution(null, fallbackBusinessId);
        }
        return customerRepository
                .findByExternalId(customerKey)
                .map(c -> new CustomerResolution(c.getStripeCustomerId(), c.getBusinessId()))
                .orElseGet(() -> {
                    if (!achEnabled) {
                        // Real (card-only) behaviour: the PI endpoint does not create the customer.
                        return new CustomerResolution(null, fallbackBusinessId);
                    }
                    // ACH (P2): create the customer up front (like the setup-intent endpoint does) so the bank
                    // account and its mandate belong to the customer when the PI is confirmed.
                    Customer customer = stripeGateway.createCustomer(
                            CustomerCreateParams.builder()
                                    .putMetadata("jBillingCustomerKey", customerKey)
                                    .build(),
                            requestOptionsProvider.getRequestOptions(fallbackBusinessId));
                    customerRepository.save(
                            StripePlatformCustomer.of(customerKey, customer.getId(), fallbackBusinessId, List.of()));
                    log.info("Created Stripe customer {} up front for customerKey {}", customer.getId(), customerKey);
                    return new CustomerResolution(customer.getId(), fallbackBusinessId);
                });
    }

    private static void validate(AmountMoney amountMoney) {
        if (amountMoney == null || amountMoney.amount() == null || amountMoney.amount() <= 0
                || amountMoney.currency() == null) {
            throw new BadRequestException("amountMoney.amount (minor units) and amountMoney.currency are required");
        }
    }
}
