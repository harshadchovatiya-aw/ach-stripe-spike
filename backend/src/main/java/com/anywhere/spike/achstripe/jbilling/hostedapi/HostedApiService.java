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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The parts of jBilling_hosted_api com.user.UserUtilities used by the four endpoints the UI calls:
 * processPayment, updatePaymentInstrument (v1), updatePaymentInstrumentAndPayment, getPaymentInstruments.
 *
 * <p>ACH (hosted-API changes from the "impact analysis" Confluence page), all gated by {brandId}.ach.provider=STRIPE
 * (AnswerForce only):
 * <ul>
 *   <li>an "ach" branch next to every "cc" branch (verifyAndVaultStripeACH)</li>
 *   <li>a new stripe-achId payment method type (ACH template, Provider=STRIPE, bank name + last4 only)</li>
 *   <li>getPaymentTypeKeyForJBillingPayment("ach") → stripe-achId</li>
 *   <li>"Payment submitted" messages – the debit settles ~4 business days later</li>
 *   <li>failures arrive later through POST /stripe/ach/payment-status (AchPaymentStatusService → DS task)</li>
 * </ul>
 */
@Service
public class HostedApiService {

    private static final Logger log = LoggerFactory.getLogger(HostedApiService.class);

    /** CommonKeys / ApplicationResources payment method type keys. */
    public static final String BT_CC_HOSTED = "btcchostedpaymentId";

    public static final String BT_CC = "bt-ccpaymentId";
    public static final String BT_ACH = "bt-achId";
    /** ACH: new CommonKeys.STRIPE_ACH */
    public static final String STRIPE_ACH = "stripe-achId";

    public static final String META_CC_NUMBER = "cc.number";
    public static final String META_CC_EXPIRY = "cc.expiry.date";
    public static final String META_CC_TYPE = "cc.type";
    public static final String META_PROVIDER = "Provider";
    public static final String META_COUNTRY = "Country";
    /** jBilling UserConfigurationKeys ACH metafields */
    public static final String META_ACH_GATEWAY_KEY = "ach.gateway.key";

    public static final String META_ACH_BANK_NAME = "ach.bank.name";
    public static final String META_ACH_ACCOUNT_NUMBER = "ach.account.number";
    public static final String META_BANK_ACCOUNT_TYPE = "Bank Account Type";
    public static final String META_ACH_ROUTING_NUMBER = "ach.routing.number";

    private static final String SUBMITTED_MESSAGE =
            "Your bank payment has been submitted. Bank payments take 3–5 business days to complete.";

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
     *       jBilling processPayment → plugin → FullPayments /createTransaction (captures a card PI; an ACH PI is
     *       already processing and is only recorded)</li>
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
                history.add("Processing one time " + paymentType.toUpperCase(Locale.US) + " payment with nonce, amount: "
                        + symbol + amount);
                if (createVaultFlag) {
                    // Real code calls PaymentHelper.processOTPaymentWithoutVault here (legacy path, not replicated).
                    log.info("createVaultFlag=true with a nonce: the spike uses the createVaultFlag=false path");
                }
                String typeKey = getPaymentTypeKeyForJBillingPayment(paymentType, brandId);
                JbPaymentInstrument singleUse = createPaymentInstrument(
                        str(req.get("nonce")), str(req.get("customerId")), typeKey,
                        isAch(paymentType) ? JbPaymentInstrument.TEMPLATE_ACH : JbPaymentInstrument.TEMPLATE_CARD);
                result = jBilling.processPayment(user, paymentAmount, singleUse, notes);
            } else if (req.get("instrumentId") != null) {
                Integer instrumentId = Integer.valueOf(String.valueOf(req.get("instrumentId")));
                JbPaymentInstrument instrument = jBilling.getPaymentInstruments(user.getId()).stream()
                        .filter(pi -> pi.getId().equals(instrumentId))
                        .findFirst()
                        .orElseThrow(() -> new BadRequestException("Payment instrument " + instrumentId + " not found"));
                history.add("Processing payment with Existing Payment Instrument (" + instrument.getPaymentMethodTypeKey()
                        + "), amount: " + symbol + amount);
                result = jBilling.processPayment(user, paymentAmount, instrument, notes);
            } else {
                throw new BadRequestException("Either nonce or instrumentId is required");
            }

            if (!result.authorization().result()) {
                throw new BadRequestException("RespFull: " + result.authorization().responseMessage());
            }
            boolean pending = result.authorization().isPendingSettlement();
            history.add((pending ? "ACH payment submitted (pending settlement)" : "Payment successful")
                    + ", transaction ID: " + result.authorization().transactionId());
            res.put("success", true);
            res.put("message", pending ? SUBMITTED_MESSAGE : "Your payment has been successfully processed.");
            res.put("transactionId", result.authorization().transactionId());
            res.put("paymentStatus", pending ? "PENDING" : "COMPLETED");
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
     * UserUtilities.updatePaymentInstrument – Stripe card / bank account saved via a platform SetupIntent
     * (paymentMethodId + setupIntentId). Replaces the instrument, no payment.
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

            if (!"cc".equalsIgnoreCase(type) && !isAch(type)) {
                throw new BadRequestException("paymentType " + type + " is not supported in the spike");
            }
            if (isAch(type)) {
                requireStripeAchBrand(brandId);
            }
            if (details.get("paymentMethodId") == null) {
                throw new BadRequestException("paymentDetails.paymentMethodId is required (Braintree nonce flow not in spike)");
            }

            String customerKey = existingCustomerKey(user);
            boolean isNewCustomerKey = false;
            if (customerKey == null) {
                // Real code: UUID.randomUUID(). The spike also accepts a customerKey generated by the UI
                // (like customerKeyFromPaymentDetails in PaymentHelper.createVaultIdByNonce).
                customerKey = Objects.requireNonNullElse(details.get("customerKey"), UUID.randomUUID().toString());
                isNewCustomerKey = true;
                log.info("registerPaymentMethodInFullPayments, creating new customerKey: {}", customerKey);
            }
            details.putAll(paymentHelper.getPaymentMethodDetails(details.get("paymentMethodId"), brandId, customerKey));

            JbPaymentInstrument instrument;
            if ("us_bank_account".equals(details.get("paymentMethodType"))) {
                // ACH: verifyAndVaultStripeACH. Phase 1 = instant verification only, so a confirmed SetupIntent means
                // a verified account (the UI only calls this endpoint once confirmSetup returned "succeeded").
                instrument = createStripeAchPaymentInstrument(details);
            } else {
                instrument = createBTCCHostedPaymentInstrument(details);
            }
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
     * UserUtilities.updatePaymentInstrumentAndPayment – "cc" / "ach" with a nonce and createVaultFlag=true.
     * For Stripe: nonce = the confirmed platform PaymentIntent with setup_future_usage=off_session.
     * <ol>
     *   <li>createVaultIdByNonce → FullPayments vaultWithPaymentIntent (saves the PM on the Stripe customer, parks
     *       the PI in pendingCapturePaymentIntentCache)</li>
     *   <li>build the instrument (btcchostedpaymentId for cards, stripe-achId for bank accounts) and replace the old one</li>
     *   <li>paymentProcess → jBilling processPayment with the new instrument → FullPayments /processPayment →
     *       captures the parked card PI (ACH: already processing, only recorded as PENDING)</li>
     * </ol>
     * The real ACH branch here has a bug (it overwrites the Braintree-vaulted instrument with
     * createACHPaymentInformation); the Stripe ACH branch below does not copy it.
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

            if (!"cc".equalsIgnoreCase(type) && !isAch(type)) {
                throw new BadRequestException("paymentType " + type + " is not supported in the spike");
            }
            if (isAch(type)) {
                requireStripeAchBrand(brandId);
            }
            if (details.get("nonce") == null) {
                throw new BadRequestException("paymentDetails.nonce is required");
            }
            String customerKey = existingCustomerKey(user);
            if (customerKey == null) {
                customerKey = Objects.requireNonNullElse(details.get("customerKey"), UUID.randomUUID().toString());
            }
            details.putAll(paymentHelper.createVaultIdByNonce(
                    brandId, details.get("nonce"), customerKey, details, String.valueOf(user.getId())));
            JbPaymentInstrument instrument = "us_bank_account".equals(details.get("paymentMethodType"))
                    ? createStripeAchPaymentInstrument(details)
                    : createBTCCHostedPaymentInstrument(details);
            JbPaymentInstrument saved = jBilling.replacePaymentInstrument(user, instrument);
            paymentHelper.updateCustomerKeyInFullPayments(customerKey, accountPin);
            history.add("New payment Instrument details: " + describe(saved));

            JBillingCoreService.PaymentResult result =
                    jBilling.processPayment(user, Money.parse(amount), saved, str(req.get("paymentNotes")));
            boolean pending = result.authorization().isPendingSettlement();
            history.add("Payment details: Amount " + symbol + amount + ", Transaction Id "
                    + result.authorization().transactionId() + (pending ? " (ACH – pending settlement)" : ""));
            if (result.authorization().result()) {
                history.add("Payment instrument updated and transaction successful.");
                res.put("success", true);
                res.put("message", pending ? SUBMITTED_MESSAGE : "Payment instrument updated and payment processed");
                res.put("transactionId", result.authorization().transactionId());
                res.put("paymentStatus", pending ? "PENDING" : "COMPLETED");
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
            // Real code defaults Provider to BRAINTREE for cards only.
            if (JbPaymentInstrument.TEMPLATE_CARD.equals(pi.getMethodTemplate())) {
                map.putIfAbsent(META_PROVIDER, "BRAINTREE");
            }
            // ACH: default Provider for ACH instruments too (stripe-achId → STRIPE, bt-achId → BRAINTREE)
            if (JbPaymentInstrument.TEMPLATE_ACH.equals(pi.getMethodTemplate())) {
                map.putIfAbsent(META_PROVIDER, STRIPE_ACH.equals(pi.getPaymentMethodTypeKey()) ? "STRIPE" : "BRAINTREE");
            }
            instruments.add(map);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("instruments", instruments);
        res.put("achProvider", properties.brand(brandId).achProvider());
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
        meta.put(META_PROVIDER, Objects.requireNonNullElse(details.get("provider"), "BRAINTREE").toUpperCase(Locale.US));
        if (details.get("country") != null) {
            meta.put(META_COUNTRY, details.get("country"));
        }
        meta.put("autopayment.authorization", "true");
        meta.values().removeIf(Objects::isNull);
        return new JbPaymentInstrument(BT_CC_HOSTED, JbPaymentInstrument.TEMPLATE_CARD, meta);
    }

    /**
     * ACH: new instrument for Stripe bank accounts (stripe-achId). Stores the Stripe PaymentMethod id in
     * ach.gateway.key, the customerKey in "BT ID" (like Stripe cards) and only the masked account number.
     */
    private JbPaymentInstrument createStripeAchPaymentInstrument(Map<String, String> details) {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put(META_ACH_GATEWAY_KEY, details.get("paymentMethodId"));
        meta.put(META_ACH_BANK_NAME, details.get("bankName"));
        meta.put(META_ACH_ACCOUNT_NUMBER, details.get("accountNumber"));
        meta.put(META_ACH_ROUTING_NUMBER, details.get("routingNumber"));
        meta.put(META_BANK_ACCOUNT_TYPE, details.get("accountType"));
        meta.put(FullPaymentsGatewayPlugin.META_BT_ID, details.get("btCustomerId"));
        meta.put(META_PROVIDER, "STRIPE");
        meta.put("autopayment.authorization", "true");
        meta.values().removeIf(v -> v == null || "null".equals(v));
        return new JbPaymentInstrument(STRIPE_ACH, JbPaymentInstrument.TEMPLATE_ACH, meta);
    }

    /** UserUtilities.createPaymentInstrument – single-use instrument carrying the nonce. */
    private static JbPaymentInstrument createPaymentInstrument(
            String nonce, String customerId, String typeKey, String template) {
        JbPaymentInstrument pi = new JbPaymentInstrument(typeKey, template, Map.of());
        pi.setSingleUseToken(nonce);
        pi.setGatewayCustomerId(customerId);
        return pi;
    }

    /** UserUtilities.getPaymentTypeKeyForJBillingPayment – ACH: stripe-achId when {brandId}.ach.provider=STRIPE. */
    private String getPaymentTypeKeyForJBillingPayment(String paymentType, String brandId) {
        return switch (paymentType.toLowerCase(Locale.US)) {
            case "cc" -> BT_CC;
            case "ach" -> properties.brand(brandId).isStripeAch() ? STRIPE_ACH : BT_ACH;
            default -> "";
        };
    }

    private void requireStripeAchBrand(String brandId) {
        if (!properties.brand(brandId).isStripeAch()) {
            throw new BadRequestException("Brand " + brandId + " uses Braintree ACH (not part of the spike)");
        }
    }

    private static boolean isAch(String paymentType) {
        return "ach".equalsIgnoreCase(paymentType);
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
