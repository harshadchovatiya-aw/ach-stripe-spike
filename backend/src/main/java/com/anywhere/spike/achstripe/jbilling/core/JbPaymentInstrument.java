package com.anywhere.spike.achstripe.jbilling.core;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Simulated jBilling payment instrument (PaymentInformationWS).
 * <ul>
 *   <li>paymentMethodTypeKey – the ApplicationResources key, e.g. btcchostedpaymentId (Stripe/BT hosted card),
 *       bt-ccpaymentId (single-use card nonce). ACH: bt-achId today, stripe-achId to be added.</li>
 *   <li>metaFields – e.g. "BT ID" (= FullPayments customerKey), "Provider" (STRIPE/BRAINTREE), card number (masked)</li>
 *   <li>singleUseToken – not persisted; a one-time "nonce" (for Stripe: a pi_... id)</li>
 * </ul>
 */
@Entity
@Table(name = "jb_payment_instrument")
public class JbPaymentInstrument {

    public static final String TEMPLATE_CARD = "Payment Card";
    public static final String TEMPLATE_ACH = "ACH";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    private Integer userId;
    private String paymentMethodTypeKey;
    private String methodTemplate;
    private boolean active = true;
    private Instant createdAt = Instant.now();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "jb_payment_instrument_meta", joinColumns = @JoinColumn(name = "instrument_id"))
    @MapKeyColumn(name = "field_name")
    @Column(name = "field_value")
    private Map<String, String> metaFields = new LinkedHashMap<>();

    @Transient
    private String singleUseToken;

    @Transient
    private String gatewayCustomerId;

    public JbPaymentInstrument() {}

    public JbPaymentInstrument(String paymentMethodTypeKey, String methodTemplate, Map<String, String> metaFields) {
        this.paymentMethodTypeKey = paymentMethodTypeKey;
        this.methodTemplate = methodTemplate;
        this.metaFields.putAll(metaFields);
    }

    public Integer getId() {
        return id;
    }

    public Integer getUserId() {
        return userId;
    }

    public void setUserId(Integer userId) {
        this.userId = userId;
    }

    public String getPaymentMethodTypeKey() {
        return paymentMethodTypeKey;
    }

    public String getMethodTemplate() {
        return methodTemplate;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Map<String, String> getMetaFields() {
        return metaFields;
    }

    public String getSingleUseToken() {
        return singleUseToken;
    }

    public void setSingleUseToken(String singleUseToken) {
        this.singleUseToken = singleUseToken;
    }

    public String getGatewayCustomerId() {
        return gatewayCustomerId;
    }

    public void setGatewayCustomerId(String gatewayCustomerId) {
        this.gatewayCustomerId = gatewayCustomerId;
    }
}
