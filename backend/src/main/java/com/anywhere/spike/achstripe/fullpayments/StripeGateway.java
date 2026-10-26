package com.anywhere.spike.achstripe.fullpayments;

import com.anywhere.spike.achstripe.common.StripeClientApiException;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentMethod;
import com.stripe.model.Refund;
import com.stripe.model.SetupIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.CustomerListPaymentMethodsParams;
import com.stripe.param.PaymentIntentCaptureParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentIntentRetrieveParams;
import com.stripe.param.PaymentIntentUpdateParams;
import com.stripe.param.PaymentMethodAttachParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.SetupIntentCreateParams;
import com.stripe.param.SetupIntentRetrieveParams;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Thin wrapper around the stripe-java SDK, combining the fullpayments *GatewaySdk classes
 * (StripeCustomerGatewaySdk, StripePaymentMethodGatewaySdk, StripeSetupIntentGatewaySdk,
 * DefaultStripePaymentIntentGateway, StripeRefundGatewaySdk). Same SDK calls as fullpayments.
 */
@Component
public class StripeGateway {

    private static final String FIELD_PAYMENT_METHOD = "payment_method";
    private static final String FIELD_LATEST_CHARGE = "latest_charge";

    // ---- customers ----

    public Customer createCustomer(CustomerCreateParams params, RequestOptions options) {
        try {
            return Customer.create(params, options);
        } catch (StripeException e) {
            throw new StripeClientApiException(e);
        }
    }

    public Optional<Customer> retrieveCustomer(String customerId, RequestOptions options) {
        try {
            Customer customer = Customer.retrieve(customerId, options);
            return Boolean.TRUE.equals(customer.getDeleted()) ? Optional.empty() : Optional.of(customer);
        } catch (StripeException e) {
            Integer status = e.getStatusCode();
            if (status != null && status == 404) {
                return Optional.empty();
            }
            throw new StripeClientApiException(e);
        }
    }

    public List<PaymentMethod> listCustomerPaymentMethods(Customer customer, RequestOptions options) {
        try {
            return customer.listPaymentMethods(CustomerListPaymentMethodsParams.builder().build(), options)
                    .getData();
        } catch (StripeException e) {
            throw new StripeClientApiException(e);
        }
    }

    // ---- payment methods ----

    public PaymentMethod retrievePaymentMethod(String paymentMethodId, RequestOptions options) {
        try {
            return PaymentMethod.retrieve(paymentMethodId, options);
        } catch (StripeException e) {
            throw new StripeClientApiException(e);
        }
    }

    public void attachPaymentMethod(PaymentMethod paymentMethod, String customerId, RequestOptions options) {
        try {
            paymentMethod.attach(PaymentMethodAttachParams.builder().setCustomer(customerId).build(), options);
        } catch (StripeException e) {
            throw new StripeClientApiException(e);
        }
    }

    public void detachPaymentMethod(String paymentMethodId, RequestOptions options) {
        try {
            PaymentMethod paymentMethod = PaymentMethod.retrieve(paymentMethodId, options);
            paymentMethod.detach(options);
        } catch (StripeException e) {
            throw new StripeClientApiException(e);
        }
    }

    // ---- setup intents ----

    public SetupIntent createSetupIntent(SetupIntentCreateParams params, RequestOptions options) {
        try {
            return SetupIntent.create(params, options);
        } catch (StripeException e) {
            throw new StripeClientApiException(e);
        }
    }

    public SetupIntent retrieveSetupIntentWithExpandedPaymentMethod(String setupIntentId, RequestOptions options) {
        try {
            var params =
                    SetupIntentRetrieveParams.builder().addExpand(FIELD_PAYMENT_METHOD).build();
            return SetupIntent.retrieve(setupIntentId, params, options);
        } catch (StripeException e) {
            throw new StripeClientApiException(e);
        }
    }

    // ---- payment intents ----

    public PaymentIntent createPaymentIntent(PaymentIntentCreateParams params, RequestOptions options) {
        try {
            return PaymentIntent.create(params, options);
        } catch (StripeException e) {
            throw new StripeClientApiException(e);
        }
    }

    public PaymentIntent retrievePaymentIntent(String paymentIntentId, RequestOptions options) {
        try {
            return PaymentIntent.retrieve(paymentIntentId, options);
        } catch (StripeException e) {
            throw new StripeClientApiException(e);
        }
    }

    public PaymentIntent retrieveWithExpandedPaymentMethod(String paymentIntentId, RequestOptions options) {
        try {
            var params = PaymentIntentRetrieveParams.builder()
                    .addExpand(FIELD_PAYMENT_METHOD)
                    .build();
            return PaymentIntent.retrieve(paymentIntentId, params, options);
        } catch (StripeException e) {
            throw new StripeClientApiException(e);
        }
    }

    public PaymentIntent updatePaymentIntent(
            PaymentIntent paymentIntent, PaymentIntentUpdateParams params, RequestOptions options) {
        try {
            return paymentIntent.update(params, options);
        } catch (StripeException e) {
            throw new StripeClientApiException(e);
        }
    }

    /** Captures and expands payment_method + latest_charge (same as fullpayments). */
    public PaymentIntent capture(PaymentIntent paymentIntent, RequestOptions options) {
        try {
            var params = PaymentIntentCaptureParams.builder()
                    .addExpand(FIELD_PAYMENT_METHOD)
                    .addExpand(FIELD_LATEST_CHARGE)
                    .build();
            return paymentIntent.capture(params, options);
        } catch (StripeException e) {
            throw new StripeClientApiException(e);
        }
    }

    // ---- refunds ----

    public Refund createRefund(RefundCreateParams params, RequestOptions options) {
        try {
            return Refund.create(params, options);
        } catch (StripeException e) {
            throw new StripeClientApiException(e);
        }
    }
}
