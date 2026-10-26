package com.anywhere.spike.achstripe.jbilling.hostedapi;

import com.anywhere.spike.achstripe.common.SpikeProperties;
import com.anywhere.spike.achstripe.jbilling.core.DsActivity;
import com.anywhere.spike.achstripe.jbilling.core.DsActivityRepository;
import com.anywhere.spike.achstripe.jbilling.core.JBillingCoreService;
import com.anywhere.spike.achstripe.jbilling.core.JbPayment;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * ACH: new jBilling hosted API handler for FullPayments ACH status callbacks (POST /stripe/ach/payment-status).
 * Mirrors TransactionSettlementDeclinedHandler (Braintree TRANSACTION_SETTLEMENT_DECLINED → DS task):
 * <ul>
 *   <li>PAYMENT_FAILED / DISPUTE_CREATED / MANDATE_INACTIVE / REFUND_FAILED → DS TASK (CC_DISPUTE type, CBS
 *       escalations) + note. Phase 2 (spike.ach.reverse-jbilling-payment-on-failure=true) also reverses the
 *       jBilling payment.</li>
 *   <li>PAYMENT_SUCCEEDED → jBilling payment settlementStatus SETTLED + note.</li>
 * </ul>
 * Idempotent per (paymentIntentId/eventId, event).
 */
@Service
public class AchPaymentStatusService {

    private static final Logger log = LoggerFactory.getLogger(AchPaymentStatusService.class);

    /** TransactionSettlementDeclinedHandler: task type CC_DISPUTE, assignee cbsEscalations.assignee, service group. */
    static final String DS_TASK_TYPE_CC_DISPUTE = "5741d050-6bee-4585-9e19-481961ed3729";

    private final JBillingCoreService jBilling;
    private final DsActivityRepository dsActivityRepository;
    private final SpikeProperties properties;
    private final Set<String> processed = ConcurrentHashMap.newKeySet();

    public AchPaymentStatusService(
            JBillingCoreService jBilling, DsActivityRepository dsActivityRepository, SpikeProperties properties) {
        this.jBilling = jBilling;
        this.dsActivityRepository = dsActivityRepository;
        this.properties = properties;
    }

    /** Same fields as FullPayments HostedApiNotifier.AchPaymentStatusNotification (separate "system"). */
    public record AchPaymentStatus(
            String event,
            String eventId,
            String paymentIntentId,
            String accountPin,
            String customerKey,
            String amount,
            String currency,
            String paymentMethodType,
            String failureCode,
            String failureMessage,
            String occurredAt) {}

    public Map<String, Object> handle(AchPaymentStatus status) {
        String key = Optional.ofNullable(status.paymentIntentId()).orElse(status.eventId()) + ":" + status.event();
        if (!processed.add(key)) {
            return response(true, "Already processed " + key);
        }
        if (status.accountPin() == null) {
            log.warn("ACH status {} without accountPin – cannot route to a jBilling account", key);
            return response(false, "No accountPin – could not find the jBilling account for " + key);
        }
        return switch (status.event()) {
            case "PAYMENT_SUCCEEDED" -> settled(status);
            case "PAYMENT_FAILED" -> failed(status);
            case "DISPUTE_CREATED" -> task(status, "ACH Payment Disputed - " + reason(status) + amountText(status));
            case "MANDATE_INACTIVE" -> task(status, "ACH Mandate Inactive - bank account can no longer be debited");
            case "REFUND_FAILED" -> task(status, "ACH Refund Failed - " + reason(status) + amountText(status));
            default -> response(false, "Unknown event " + status.event());
        };
    }

    private Map<String, Object> settled(AchPaymentStatus status) {
        Optional<JbPayment> payment = jBilling.markSettled(status.paymentIntentId());
        dsActivityRepository.save(new DsActivity(status.accountPin(), DsActivity.NOTE, "ACH payment settled",
                "ACH payment " + status.paymentIntentId() + " settled" + amountText(status)
                        + payment.map(p -> " – jBilling payment #" + p.getId()).orElse("")));
        return response(true, "jBilling payment marked SETTLED");
    }

    private Map<String, Object> failed(AchPaymentStatus status) {
        boolean reverse = properties.ach() != null && properties.ach().reverseJbillingPaymentOnFailure();
        Optional<JbPayment> payment = jBilling.markFailed(status.paymentIntentId(), status.failureMessage(), reverse);
        String title = "ACH Payment Settlement Declined - " + reason(status) + amountText(status);
        String text = taskText(status)
                + "\njBilling payment: " + payment.map(p -> "#" + p.getId() + " → " + p.getSettlementStatus()).orElse("not found")
                + (reverse ? "\nPhase 2: the jBilling payment was reversed (owing balance restored)."
                        : "\nPhase 1 (Braintree parity): the jBilling payment was NOT reversed – follow up manually.");
        dsActivityRepository.save(new DsActivity(status.accountPin(), DsActivity.TASK, title, text));
        log.info("DS task created for {}: {}", status.accountPin(), title);
        return response(true, "DS task created" + (reverse ? " and jBilling payment reversed" : ""));
    }

    private Map<String, Object> task(AchPaymentStatus status, String title) {
        dsActivityRepository.save(new DsActivity(status.accountPin(), DsActivity.TASK, title, taskText(status)));
        log.info("DS task created for {}: {}", status.accountPin(), title);
        return response(true, "DS task created");
    }

    private static String taskText(AchPaymentStatus status) {
        return "Task type: CC_DISPUTE (" + DS_TASK_TYPE_CC_DISPUTE + "), assignee: cbsEscalations.assignee"
                + "\nEvent: " + status.event()
                + "\nPaymentIntent: " + status.paymentIntentId()
                + "\ncustomerKey: " + status.customerKey()
                + "\nPayment method: " + status.paymentMethodType()
                + "\nReason: " + status.failureCode() + " – " + status.failureMessage();
    }

    private static String reason(AchPaymentStatus status) {
        String reason = Optional.ofNullable(status.failureMessage()).orElse(status.failureCode());
        if (reason == null) {
            return "Unknown reason";
        }
        return reason.length() > 50 ? reason.substring(0, 50) + "..." : reason;
    }

    private static String amountText(AchPaymentStatus status) {
        return status.amount() != null ? " (Amount: $" + status.amount() + ")" : "";
    }

    private static Map<String, Object> response(boolean success, String message) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", success);
        res.put("message", message);
        return res;
    }
}
