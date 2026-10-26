package com.anywhere.spike.achstripe.jbilling.core;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Simulated jBilling payment (PaymentWS) – what "the payment is recorded in jBilling" means in this spike.
 * ACH: settlementStatus tracks the bank debit after jBilling already recorded the payment as Successful
 * (accept-on-submit, like Braintree): PENDING → SETTLED, or FAILED (and REVERSED when phase-2 reversal is on).
 */
@Entity
@Table(name = "jb_payment")
public class JbPayment {

    public static final String RESULT_SUCCESSFUL = "Successful";
    public static final String RESULT_FAILED = "Failed";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    private Integer userId;
    private BigDecimal amount;
    private boolean refund;
    private Integer linkedPaymentId;
    private String result;
    /** Gateway transaction id – for Stripe the pi_... (or re_... for refunds). */
    private String transactionId;
    private String paymentMethod;
    private String notes;
    private String gatewayMessage;
    private Instant createdAt = Instant.now();
    private String settlementStatus;
    private String paymentMethodType;

    public String getSettlementStatus() {
        return settlementStatus;
    }

    public void setSettlementStatus(String settlementStatus) {
        this.settlementStatus = settlementStatus;
    }

    public String getPaymentMethodType() {
        return paymentMethodType;
    }

    public void setPaymentMethodType(String paymentMethodType) {
        this.paymentMethodType = paymentMethodType;
    }

    protected JbPayment() {}

    public JbPayment(
            Integer userId,
            BigDecimal amount,
            boolean refund,
            Integer linkedPaymentId,
            String result,
            String transactionId,
            String paymentMethod,
            String notes,
            String gatewayMessage) {
        this.userId = userId;
        this.amount = amount;
        this.refund = refund;
        this.linkedPaymentId = linkedPaymentId;
        this.result = result;
        this.transactionId = transactionId;
        this.paymentMethod = paymentMethod;
        this.notes = notes;
        this.gatewayMessage = gatewayMessage;
    }

    public Integer getId() {
        return id;
    }

    public Integer getUserId() {
        return userId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public boolean isRefund() {
        return refund;
    }

    public Integer getLinkedPaymentId() {
        return linkedPaymentId;
    }

    public String getResult() {
        return result;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getPaymentMethod() {
        return paymentMethod;
    }

    public String getNotes() {
        return notes;
    }

    public String getGatewayMessage() {
        return gatewayMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
