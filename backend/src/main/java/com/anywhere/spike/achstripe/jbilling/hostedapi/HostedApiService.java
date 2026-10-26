package com.anywhere.spike.achstripe.jbilling.hostedapi;

import com.anywhere.spike.achstripe.common.BadRequestException;
import com.anywhere.spike.achstripe.common.Money;
import com.anywhere.spike.achstripe.common.SpikeProperties;
import com.anywhere.spike.achstripe.jbilling.core.DsActivity;
import com.anywhere.spike.achstripe.jbilling.core.DsActivityRepository;
import com.anywhere.spike.achstripe.jbilling.core.FullPaymentsGatewayPlugin;
import com.anywhere.spike.achstripe.jbilling.core.JBillingCoreService;
import com.anywhere.spike.achstripe.jbilling.core.JbPaymentInstrument;
import com.anywhere.spike.achstripe.jbilling.core.JbUser;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The card parts of jBilling_hosted_api com.user.UserUtilities used by the four endpoints the UI calls:
 * processPayment, updatePaymentInstrument (v1), updatePaymentInstrumentAndPayment, getPaymentInstruments.
 *
 * <p>ACH (hosted-API changes, see the "impact analysis" Confluence page): add a "{brandId}.ach.provider=STRIPE"
 * switch (AnswerForce only), an "ach" branch next to every "cc" branch below (verifyAndVaultStripeACH),
 * a new stripe-achId payment method type, and a callback endpoint that creates a DS task when an ACH payment fails.
 */
@Service
public class HostedApiService {

    private static final Logger log = LoggerFactory.getLogger(HostedApiService.class);

    /** CommonKeys / ApplicationResources payment method type keys. */
    public static final String BT_CC_HOSTED = "btcchostedpaymentId";

    public static final String BT_CC = "bt-ccpaymentId";
    public static final String META_CC_NUMBER = "cc.number";
    public static final String META_CC_EXPIRY = "cc.expiry.date";
    public static final String META_CC_TYPE = "cc.type";
    public static final String META_PROVIDER = "Provider";
    public static final String META_COUNTRY = "Country";

    private final JBillingCoreService jBilling;
    private final PaymentHelper paymentHelper;
    private final DsActivityRepository dsActivityRepository;
    private final SpikeProperties properties;

    public HostedApiService(
            JBillingCoreService jBilling,
            PaymentHelper paymentHelper,
            DsActivityRepository dsActivityRepository,
            SpikeProperties properties) {
        this.jBilling = jBilling;
        this.paymentHelper = paymentHelper;
        this.dsActivityRepository = dsActivityRepository;
        this.properties = properties;
    }

    // ------------------------------------------------------------------ POST /processPayment

    /**
     * UserUtilities.processPayment.
     * <ul>
     *   <li>nonce + createVaultFlag=false → single-use instrument (type from getPaymentTypeKeyForJBillingPayment) →
     *       jBilling processPayment → plugin → FullPayments /createTransaction (captures the platform PI)</li>
     *   <li>instrumentId → getInstrumentAndProcess → jBilling processPayment with the saved instrument → plugin →
     *       FullPayments /processPayment (off-session PI)</li>
     * </ul>
     */
    public Map<String, Object> processPayment(Map<String, Object> req) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", false);
        List<String> history = new ArrayList<>();
        String accountPin = str(req.get("accountPin"));
        String amount = str(req.get("paymentAmount"));
        try {
            String brandId = str(req.get("brandId"));
            String paymentType = Objects.requireNonNullElse(str(req.get("paymentType")), "cc");
            boolean createVaultFlag = !Boolean.FALSE.equals(req.get("createVaultFlag"));
            String notes = str(req.get("paymentNotes"));
            String symbol = properties.brand(brandId).currencySymbol();
            JbUser user = jBilling.getUserByAccountPin(accountPin);
            BigDecimal paymentAmount = Money.parse(amount);
            history.add("Payment Information: requestedBy " + req.getOrDefault("requestedBy", "spike-ui"));

            JBillingCoreService.PaymentResult result;
            if (str(req.get("nonce")) != null) {
                history.add("Processing one time payment with nonce, amount: " + symbol + amount);
                if (createVaultFlag) {
                    // Real code calls PaymentHelper.processOTPaymentWithoutVault here (legacy path, not replicated).
                    log.info("createVaultFlag=true with a nonce: the spike uses the createVaultFlag=false path");
                }
                JbPaymentInstrument singleUse = createPaymentInstrument(
                        str(req.get("nonce")), str(req.get("customerId")), getPaymentTypeKeyForJBillingPayment(paymentType));
                result = jBilling.processPayment(user, paymentAmount, singleUse, notes);
            } else if (req.get("instrumentId") != null) {
                Integer instrumentId = Integer.valueOf(String.valueOf(req.get("instrumentId")));
                JbPaymentInstrument instrument = jBilling.getPaymentInstruments(user.getId()).stream()
                        .filter(pi -> pi.getId().equals(instrumentId))
                        .findFirst()
                        .orElseThrow(() -> new BadRequestException("Payment instrument " + instrumentId + " not found"));
                history.add("Processing payment with Existing Payment Instrument, amount: " + symbol + amount);
                result = jBilling.processPayment(user, paymentAmount, instrument, notes);
            } else {
                throw new BadRequestException("Either nonce or instrumentId is required");
            }

            if (!result.authorization().result()) {
                throw new BadRequestException("RespFull: " + result.authorization().responseMessage());
            }
            history.add("Payment successful, transaction ID: " + result.authorization().transactionId());
            res.put("success", true);
            res.put("message", "Your payment has been successfully processed.");
            res.put("transactionId", result.authorization().transactionId());
            res.put("jbillingPaymentId", result.payment().getId());
            res.put("owingBalance", symbol + jBilling.getUserByAccountPin(accountPin).getOwingBalance());
        } catch (RuntimeException e) {
            log.warn("processPayment failed: {}", e.getMessage());
            res.put("message", e.getMessage());
            history.add("Payment processing has failed for amount: " + amount + ", Reason: " + e.getMessage());
        }
        writeNote(accountPin, "Payment", history);
        return res;
    }

    // ------------------------------------------------------------------ PUT /v1/updatePaymentInstrument

    /**
     * UserUtilities.updatePaymentInstrument – "cc" branch with paymentMethodId (Stripe card saved via a
     * platform SetupIntent). Replaces the instrument, no payment.
     */
    public Map<String, Object> updatePaymentInstrument(Map<String, Object> req) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", false);
        List<String> history = new ArrayList<>();
        history.add("Payment Instrument Update Information: requestedBy " + req.getOrDefault("requestedBy", "spike-ui"));
        String accountPin = str(req.get("accountPin"));
        try {
            String brandId = str(req.get("brandId"));
            String type = Objects.requireNonNullElse(str(req.get("paymentType")), "cc");
            JbUser user = jBilling.getUserByAccountPin(accountPin);
            Map<String, String> details = details(req);

            if (!"cc".equalsIgnoreCase(type)) {
                // ACH: "ach" + paymentMethodId + brand on Stripe → verifyAndVaultStripeACH (stripe-achId instrument)
                throw new BadRequestException("paymentType " + type + " is not implemented in the spike yet (ACH TODO)");
            }
            if (details.get("paymentMethodId") == null) {
                throw new BadRequestException("paymentDetails.paymentMethodId is required (Braintree nonce flow not in spike)");
            }

            String customerKey = existingCustomerKey(user);
            boolean isNewCustomerKey = false;
            if (customerKey == null) {
                customerKey = UUID.randomUUID().toString();
                isNewCustomerKey = true;
                log.info("registerPaymentMethodInFullPayments, creating new customerKey: {}", customerKey);
            }
            details.putAll(paymentHelper.getPaymentMethodDetails(details.get("paymentMethodId"), brandId, customerKey));
            JbPaymentInstrument instrument = createBTCCHostedPaymentInstrument(details);
            if (isNewCustomerKey && details.get("setupIntentId") != null) {
                log.info("registerPaymentMethodInFullPayments, registering customerKey {} with setupIntent {}",
                        customerKey, details.get("setupIntentId"));
                paymentHelper.createVaultIdByNonce(
                        brandId, details.get("setupIntentId"), customerKey, details, String.valueOf(user.getId()));
            }
            JbPaymentInstrument saved = jBilling.replacePaymentInstrument(user, instrument);
            paymentHelper.updateCustomerKeyInFullPayments(customerKey, accountPin);

            history.add("New Payment Instrument: " + describe(saved));
            history.add("Payment Instrument updated successfully.");
            res.put("success", true);
            res.put("message", "Payment instrument updated successfully");
            res.put("instrumentId", saved.getId());
            res.put("customerKey", customerKey);
        } catch (RuntimeException e) {
            log.warn("updatePaymentInstrument failed: {}", e.getMessage());
            res.put("message", e.getMessage());
            history.add("Payment Instrument Update failed: " + e.getMessage());
        }
        writeNote(accountPin, "Payment instrument update", history);
        return res;
    }

    // ------------------------------------------------------------------ PUT /updatePaymentInstrument

    /**
     * UserUtilities.updatePaymentInstrumentAndPayment – "cc" branch with a nonce and createVaultFlag=true.
     * For Stripe: nonce = the confirmed (authorized) platform PaymentIntent with setup_future_usage=off_session.
     * <ol>
     *   <li>createVaultIdByNonce → FullPayments vaultWithPaymentIntent (saves PM on the Stripe customer, parks the PI
     *       in pendingCapturePaymentIntentCache)</li>
     *   <li>build the btcchostedpaymentId instrument and replace the old one</li>
     *   <li>paymentProcess → jBilling processPayment with the new instrument → FullPayments /processPayment →
     *       captures the parked PI</li>
     * </ol>
     */
    public Map<String, Object> updatePaymentInstrumentAndPayment(Map<String, Object> req) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", false);
        List<String> history = new ArrayList<>();
        String accountPin = str(req.get("accountPin"));
        String amount = str(req.get("amount"));
        try {
            String brandId = str(req.get("brandId"));
            String type = Objects.requireNonNullElse(str(req.get("paymentType")), "cc");
            String symbol = properties.brand(brandId).currencySymbol();
            JbUser user = jBilling.getUserByAccountPin(accountPin);
            Map<String, String> details = details(req);

            if (!"cc".equalsIgnoreCase(type)) {
                // ACH: same Stripe ACH branch here; also fix the existing bug where the ACH branch overwrites the
                // Braintree-vaulted instrument with createACHPaymentInformation(...)
                throw new BadRequestException("paymentType " + type + " is not implemented in the spike yet (ACH TODO)");
            }
            if (details.get("nonce") == null) {
                throw new BadRequestException("paymentDetails.nonce is required");
            }
            String customerKey = existingCustomerKey(user);
            if (customerKey == null) {
                customerKey = UUID.randomUUID().toString();
            }
            details.putAll(paymentHelper.createVaultIdByNonce(
                    brandId, details.get("nonce"), customerKey, details, String.valueOf(user.getId())));
            JbPaymentInstrument saved = jBilling.replacePaymentInstrument(user, createBTCCHostedPaymentInstrument(details));
            paymentHelper.updateCustomerKeyInFullPayments(customerKey, accountPin);
            history.add("New payment Instrument details: " + describe(saved));

            JBillingCoreService.PaymentResult result =
                    jBilling.processPayment(user, Money.parse(amount), saved, str(req.get("paymentNotes")));
            history.add("Payment details: Amount " + symbol + amount + ", Transaction Id "
                    + result.authorization().transactionId());
            if (result.authorization().result()) {
                history.add("Payment instrument updated and transaction successful.");
                res.put("success", true);
                res.put("message", "Payment instrument updated and payment processed");
                res.put("transactionId", result.authorization().transactionId());
            } else {
                history.add("Payment instrument updated but transaction failed.");
                res.put("message", result.authorization().responseMessage());
            }
            res.put("instrumentId", saved.getId());
            res.put("jbillingPaymentId", result.payment().getId());
            res.put("owingBalance", symbol + jBilling.getUserByAccountPin(accountPin).getOwingBalance());
        } catch (RuntimeException e) {
            log.warn("updatePaymentInstrumentAndPayment failed: {}", e.getMessage());
            res.put("message", e.getMessage());
            history.add("Updating payment instrument has failed, with message: " + e.getMessage());
        }
        writeNote(accountPin, "Payment instrument update + payment", history);
        return res;
    }

    // ------------------------------------------------------------------ GET /getPaymentInstruments

    /** UserUtilities.getPaymentInstruments + sortAndUpdateTypeInInstruments. */
    public Map<String, Object> getPaymentInstruments(String accountPin, String brandId) {
        JbUser user = jBilling.getUserByAccountPin(accountPin);
        List<Map<String, Object>> instruments = new ArrayList<>();
        for (JbPaymentInstrument pi : jBilling.getPaymentInstruments(user.getId())) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", pi.getId());
            map.put("method_template", pi.getMethodTemplate());
            map.put("paymentMethodTypeKey", pi.getPaymentMethodTypeKey());
            map.put("type", JbPaymentInstrument.TEMPLATE_CARD.equals(pi.getMethodTemplate()) ? "cc"
                    : JbPaymentInstrument.TEMPLATE_ACH.equals(pi.getMethodTemplate()) ? "ach" : null);
            map.putAll(pi.getMetaFields());
            // Real code defaults Provider to BRAINTREE for cards only. ACH: add a default for ACH instruments too.
            if (JbPaymentInstrument.TEMPLATE_CARD.equals(pi.getMethodTemplate())) {
                map.putIfAbsent(META_PROVIDER, "BRAINTREE");
            }
            instruments.add(map);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("instruments", instruments);
        return res;
    }

    // ------------------------------------------------------------------ helpers

    /** UserUtilities.createBTCCHostedPaymentInstrument (Stripe or BT hosted card). */
    private JbPaymentInstrument createBTCCHostedPaymentInstrument(Map<String, String> details) {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put(META_CC_NUMBER, details.get("cardNumber"));
        meta.put(META_CC_EXPIRY, details.get("expDate"));
        meta.put(META_CC_TYPE, details.get("cardType"));
        meta.put(FullPaymentsGatewayPlugin.META_BT_ID, details.get("btCustomerId"));
        meta.put(META_PROVIDER, Objects.requireNonNullElse(details.get("provider"), "BRAINTREE").toUpperCase());
        if (details.get("country") != null) {
            meta.put(META_COUNTRY, details.get("country"));
        }
        meta.put("autopayment.authorization", "true");
        meta.values().removeIf(Objects::isNull);
        return new JbPaymentInstrument(BT_CC_HOSTED, JbPaymentInstrument.TEMPLATE_CARD, meta);
    }

    /** UserUtilities.createPaymentInstrument – single-use instrument carrying the nonce. */
    private static JbPaymentInstrument createPaymentInstrument(String nonce, String customerId, String typeKey) {
        JbPaymentInstrument pi = new JbPaymentInstrument(typeKey, JbPaymentInstrument.TEMPLATE_CARD, Map.of());
        pi.setSingleUseToken(nonce);
        pi.setGatewayCustomerId(customerId);
        return pi;
    }

    /** UserUtilities.getPaymentTypeKeyForJBillingPayment. ACH: return STRIPE_ACH for AnswerForce. */
    private static String getPaymentTypeKeyForJBillingPayment(String paymentType) {
        return switch (paymentType.toLowerCase()) {
            case "cc" -> BT_CC;
            case "ach" -> "bt-achId";
            default -> "";
        };
    }

    /** UserUtilities.getCustomerKey – the "BT ID" of the existing instrument. */
    private String existingCustomerKey(JbUser user) {
        return jBilling.getPaymentInstruments(user.getId()).stream()
                .map(pi -> pi.getMetaFields().get(FullPaymentsGatewayPlugin.META_BT_ID))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> details(Map<String, Object> req) {
        Map<String, String> details = new HashMap<>();
        Object raw = req.get("paymentDetails");
        if (raw instanceof Map<?, ?> map) {
            ((Map<String, Object>) map).forEach((k, v) -> {
                if (v != null) {
                    details.put(k, String.valueOf(v));
                }
            });
        }
        return details;
    }

    private static String describe(JbPaymentInstrument pi) {
        return pi.getPaymentMethodTypeKey() + " " + pi.getMetaFields();
    }

    private void writeNote(String accountPin, String title, List<String> history) {
        if (accountPin != null && !history.isEmpty()) {
            dsActivityRepository.save(new DsActivity(accountPin, DsActivity.NOTE, title, String.join("\n", history)));
        }
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value);
        return s.isBlank() || "null".equals(s) ? null : s;
    }
}
