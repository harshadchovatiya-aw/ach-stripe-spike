package com.anywhere.spike.achstripe.jbilling.core;

import com.anywhere.spike.achstripe.common.BadRequestException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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
     */
    @Transactional
    public PaymentResult processPayment(JbUser user, BigDecimal amount, JbPaymentInstrument instrument, String notes) {
        JbPaymentInstrument toCharge = instrument != null ? instrument : defaultInstrument(user);
        FullPaymentsGatewayPlugin.PaymentAuthorization auth = plugin.charge(user, toCharge, amount);

        String method = describe(toCharge);
        JbPayment payment = paymentRepository.save(new JbPayment(
                user.getId(),
                amount,
                false,
                null,
                auth.result() ? JbPayment.RESULT_SUCCESSFUL : JbPayment.RESULT_FAILED,
                auth.transactionId(),
                method,
                notes,
                auth.responseMessage()));
        if (auth.result()) {
            // ACH: Braintree ACH is also treated as successful at this point (submitted for settlement).
            user.setOwingBalance(user.getOwingBalance().subtract(amount));
            userRepository.save(user);
        }
        log.info("jBilling payment {} for user {}: {} {}", payment.getId(), user.getAccountPin(), payment.getResult(),
                auth.transactionId());
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
        JbUser user = userRepository.findById(original.getUserId()).orElseThrow();
        BigDecimal refundAmount = amount != null ? amount : original.getAmount();
        FullPaymentsGatewayPlugin.PaymentAuthorization auth =
                plugin.refund(user, original.getTransactionId(), refundAmount);
        JbPayment refund = paymentRepository.save(new JbPayment(
                user.getId(),
                refundAmount,
                true,
                original.getId(),
                auth.result() ? JbPayment.RESULT_SUCCESSFUL : JbPayment.RESULT_FAILED,
                auth.transactionId(),
                original.getPaymentMethod(),
                "Refund of payment " + original.getId(),
                auth.responseMessage()));
        if (auth.result()) {
            user.setOwingBalance(user.getOwingBalance().add(refundAmount));
            userRepository.save(user);
        }
        return new PaymentResult(refund, auth);
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
        return meta.getOrDefault("Provider", "?") + " " + meta.getOrDefault("cc.type", "")
                + " " + meta.getOrDefault("cc.number", "");
    }
}
