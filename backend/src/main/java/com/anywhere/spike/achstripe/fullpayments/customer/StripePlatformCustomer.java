package com.anywhere.spike.achstripe.fullpayments.customer;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Copy of fullpayments com.acti.payment.stripe.domain.StripePlatformCustomer.
 * Represents a customer of a platform account (our merchant / jBilling account).
 * <ul>
 *   <li>externalId – the jBilling customerKey (stored in the "BT ID" metafield of the jBilling instrument)</li>
 *   <li>merchantId – the jBilling accountPin (set via PATCH /api/stripe/v4/platform/customers)</li>
 *   <li>businessId – which Stripe account the customer lives in (e.g. stripe-answerforce-us-platform)</li>
 *   <li>paymentMethodIds – kept to exactly one by the payment_method.attached webhook handler</li>
 * </ul>
 */
@Entity
@Table(name = "stripe_platform_customer")
public class StripePlatformCustomer {

    @Id
    private String id;

    @Column(name = "external_id", nullable = false, unique = true)
    private String externalId;

    @Column(name = "merchant_id")
    private String merchantId;

    @Column(name = "stripe_customer_id", nullable = false)
    private String stripeCustomerId;

    @Column(name = "business_id", nullable = false)
    private String businessId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "stripe_platform_customer_pm", joinColumns = @JoinColumn(name = "customer_id"))
    @OrderColumn(name = "pm_order")
    @Column(name = "payment_method_id")
    private List<String> paymentMethodIds = new ArrayList<>();

    private Instant createdAt = Instant.now();
    private Instant updatedAt = createdAt;

    protected StripePlatformCustomer() {}

    public static StripePlatformCustomer of(
            String externalId, String stripeCustomerId, String businessId, List<String> paymentMethodIds) {
        var c = new StripePlatformCustomer();
        c.id = UUID.randomUUID().toString();
        c.externalId = externalId;
        c.stripeCustomerId = stripeCustomerId;
        c.businessId = businessId;
        c.paymentMethodIds.addAll(paymentMethodIds);
        return c;
    }

    public void addPaymentMethodId(String paymentMethodId) {
        if (paymentMethodId == null || paymentMethodId.isBlank()) {
            throw new IllegalArgumentException("paymentMethodId cannot be null or empty");
        }
        paymentMethodIds.add(paymentMethodId);
        touch();
    }

    public void removePaymentMethodId(String paymentMethodId) {
        paymentMethodIds.remove(paymentMethodId);
        touch();
    }

    public void setMerchantId(String merchantId) {
        if (this.merchantId != null && !this.merchantId.equals(merchantId)) {
            throw new IllegalArgumentException("MerchantId already set");
        }
        this.merchantId = merchantId;
        touch();
    }

    private void touch() {
        updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getStripeCustomerId() {
        return stripeCustomerId;
    }

    public String getBusinessId() {
        return businessId;
    }

    public List<String> getPaymentMethodIds() {
        return paymentMethodIds;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
