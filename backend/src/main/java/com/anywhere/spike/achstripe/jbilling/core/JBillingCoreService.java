package com.anywhere.spike.achstripe.jbilling.core;

import com.anywhere.spike.achstripe.common.BadRequestException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A tiny simulated jBilling core (the JbillingAPI the hosted API calls): users, payment instruments, payments,
 * and the payment process that hands charges to the payment-task plugin.
 */
@Service
public class JBillingCoreService {

    private static final Logger log = LoggerFactory.getLogger(JBillingCoreService.class);

    public static final String SETTLEMENT_PENDING = "PENDING";
    public static final String SETTLEMENT_SETTLED = "SETTLED";
    public static final String SETTLEMENT_FAILED = "FAILED";
    public static final String SETTLEMENT_REVERSED = "REVERSED";

    private final JbUserRepository userRepository;
    private final JbPaymentInstrumentRepository instrumentRepository;
    private final JbPaymentRepository paymentRepository;
    private final FullPaymentsGatewayPlugin plugin;

    public JBillingCoreService(
            JbUserRepository userRepository,
            JbPaymentInstrumentRepository instrumentRepository,
            JbPaymentRepository paymentRepository,
            FullPaymentsGatewayPlugin plugin) {
        this.userRepository = userRepository;
        this.instrumentRepository = instrumentRepository;
        this.paymentRepository = paymentRepository;
        this.plugin = plugin;
    }

    /** api.getUserByCustomerMetaField(accountPin, "Account PIN") */
    public JbUser getUserByAccountPin(String accountPin) {
        return userRepository
                .findByAccountPin(accountPin)
                .orElseThrow(() -> new BadRequestException("User not found for accountPin: " + accountPin));
    }

    public List<JbPaymentInstrument> getPaymentInstruments(Integer userId) {
        return instrumentRepository.findByUserIdAndActiveTrueOrderByIdDesc(userId);
    }

    /**
     * api.addPaymentInstrument + removeExistingPaymentInstruments – the hosted API keeps a single instrument
     * (UserUtilities.replacePaymentInstrument).
     */
    @Transactional
    public JbPaymentInstrument replacePaymentInstrument(JbUser user, JbPaymentInstrument instrument) {
        for (JbPaymentInstrument existing : getPaymentInstruments(user.getId())) {
            existing.setActive(false);
            instrumentRepository.save(existing);
        }
        instrument.setUserId(user.getId());
        return instrumentRepository.save(instrument);
    }

    /**
     * api.processPayment(...) – charges either the given (possibly single-use) instrument or the user's default
     * instrument through the plugin and records a jBilling payment.
     *
     * <p>ACH: the payment is recorded as Successful as soon as the bank debit is submitted (same as Braintree
     * ACH today) and its settlementStatus is PENDING until FullPayments reports settlement or failure.
     */
    @Transactional
    public PaymentResult processPayment(JbUser user, BigDecimal amount, JbPaymentInstrument instrument, String notes) {
        JbPaymentInstrument toCharge = instrument != null ? instrument : defaultInstrument(user);
        FullPaymentsGatewayPlugin.PaymentAuthorization auth = plugin.charge(user, toCharge, amount);

        JbPayment payment = new JbPayment(
                user.getId(),
                amount,
                false,
                null,
                auth.result() ? JbPayment.RESULT_SUCCESSFUL : JbPayment.RESULT_FAILED,
                auth.transactionId(),
                describe(toCharge),
                notes,
                auth.result() && auth.isPendingSettlement()
                        ? "Submitted to the bank – settles in ~4 business days"
                        : auth.responseMessage());
        payment.setPaymentMethodType(auth.paymentMethodType());
        if (auth.result() && auth.isPendingSettlement()) {
            payment.setSettlementStatus(SETTLEMENT_PENDING);
        }
        payment = paymentRepository.save(payment);
        if (auth.result()) {
            user.setOwingBalance(user.getOwingBalance().subtract(amount));
            userRepository.save(user);
        }
        log.info("jBilling payment {} for user {}: {} {} ({})", payment.getId(), user.getAccountPin(),
                payment.getResult(), auth.transactionId(), auth.responseCode());
        return new PaymentResult(payment, auth);
    }

    @Transactional
    public PaymentResult refund(Integer paymentId, BigDecimal amount) {
        JbPayment original = paymentRepository
                .findById(paymentId)
                .orElseThrow(() -> new BadRequestException("Payment not found: " + paymentId));
        if (original.isRefund() || !JbPayment.RESULT_SUCCESSFUL.equals(original.getResult())) {
            throw new BadRequestException("Only successful payments can be refunded");
        }
        if (SETTLEMENT_PENDING.equals(original.getSettlementStatus())) {
            throw new BadRequestException("The ACH payment is still pending settlement – it can be refunded after it settles");
        }
        if (SETTLEMENT_FAILED.equals(original.getSettlementStatus())
                || SETTLEMENT_REVERSED.equals(original.getSettlementStatus())) {
            throw new BadRequestException("The ACH payment failed – there is nothing to refund");
        }
        JbUser user = userRepository.findById(original.getUserId()).orElseThrow();
        BigDecimal refundAmount = amount != null ? amount : original.getAmount();
        FullPaymentsGatewayPlugin.PaymentAuthorization auth =
                plugin.refund(user, original.getTransactionId(), refundAmount);
        JbPayment refund = new JbPayment(
                user.getId(),
                refundAmount,
                true,
                original.getId(),
                auth.result() ? JbPayment.RESULT_SUCCESSFUL : JbPayment.RESULT_FAILED,
                auth.transactionId(),
                original.getPaymentMethod(),
                "Refund of payment " + original.getId(),
                auth.result() ? "Refund " + auth.responseCode() : auth.responseMessage());
        refund.setPaymentMethodType(original.getPaymentMethodType());
        refund = paymentRepository.save(refund);
        if (auth.result()) {
            user.setOwingBalance(user.getOwingBalance().add(refundAmount));
            userRepository.save(user);
        }
        return new PaymentResult(refund, auth);
    }

    /** ACH: settlement confirmed (payment_intent.succeeded). */
    @Transactional
    public Optional<JbPayment> markSettled(String transactionId) {
        return paymentRepository.findFirstByTransactionIdAndRefundFalse(transactionId).map(payment -> {
            payment.setSettlementStatus(SETTLEMENT_SETTLED);
            return paymentRepository.save(payment);
        });
    }

    /**
     * ACH: the bank returned the debit. Phase 1 (Braintree parity) only flags it; phase 2 (reverse=true) also
     * records a reversal so the owing balance is correct again.
     */
    @Transactional
    public Optional<JbPayment> markFailed(String transactionId, String reason, boolean reverse) {
        return paymentRepository.findFirstByTransactionIdAndRefundFalse(transactionId).map(payment -> {
            if (reverse && !SETTLEMENT_REVERSED.equals(payment.getSettlementStatus())) {
                JbUser user = userRepository.findById(payment.getUserId()).orElseThrow();
                JbPayment reversal = new JbPayment(
                        user.getId(),
                        payment.getAmount(),
                        true,
                        payment.getId(),
                        JbPayment.RESULT_SUCCESSFUL,
                        transactionId,
                        payment.getPaymentMethod(),
                        "Reversal – ACH payment " + payment.getId() + " returned by the bank",
                        reason);
                reversal.setPaymentMethodType(payment.getPaymentMethodType());
                reversal.setSettlementStatus(SETTLEMENT_REVERSED);
                paymentRepository.save(reversal);
                user.setOwingBalance(user.getOwingBalance().add(payment.getAmount()));
                userRepository.save(user);
                payment.setSettlementStatus(SETTLEMENT_REVERSED);
            } else if (!SETTLEMENT_REVERSED.equals(payment.getSettlementStatus())) {
                payment.setSettlementStatus(SETTLEMENT_FAILED);
            }
            return paymentRepository.save(payment);
        });
    }

    public JbPaymentInstrument defaultInstrument(JbUser user) {
        return getPaymentInstruments(user.getId()).stream()
                .findFirst()
                .orElseThrow(() -> new BadRequestException("No payment instrument on file for " + user.getAccountPin()));
    }

    public record PaymentResult(JbPayment payment, FullPaymentsGatewayPlugin.PaymentAuthorization authorization) {}

    static String describe(JbPaymentInstrument instrument) {
        if (instrument.getSingleUseToken() != null) {
            return "One-time (" + instrument.getPaymentMethodTypeKey() + ", nonce " + instrument.getSingleUseToken() + ")";
        }
        Map<String, String> meta = instrument.getMetaFields();
        if (JbPaymentInstrument.TEMPLATE_ACH.equals(instrument.getMethodTemplate())) {
            return meta.getOrDefault("Provider", "?") + " ACH " + meta.getOrDefault("ach.bank.name", "")
                    + " " + meta.getOrDefault("ach.account.number", "");
        }
        return meta.getOrDefault("Provider", "?") + " " + meta.getOrDefault("cc.type", "")
                + " " + meta.getOrDefault("cc.number", "");
    }
}
