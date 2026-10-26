package com.anywhere.spike.achstripe.fullpayments.facade;

import com.anywhere.spike.achstripe.fullpayments.customer.StripePlatformCustomer;
import com.anywhere.spike.achstripe.fullpayments.customer.StripePlatformCustomerRepository;
import com.anywhere.spike.achstripe.fullpayments.customer.StripePlatformCustomerService;
import com.anywhere.spike.achstripe.fullpayments.intent.PlatformIntentCache;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Copy of fullpayments com.acti.braintree.Helper.StripeBraintreePaymentHelper.
 *
 * <p>"A helper class was created to support the migration from Braintree to Stripe. Since changing the
 * FullPayments–Braintree client is difficult (JBilling), we decided to adapt the existing endpoints to use Stripe
 * while still supporting all current functionalities."
 *
 * <p>In the real code every method falls back to BraintreeHelper when the customer / nonce is not a Stripe one.
 * Braintree is not part of this spike, so those fallbacks return an error.
 */
@Component
public class StripeBraintreePaymentHelper {

    private static final Logger log = LoggerFactory.getLogger(StripeBraintreePaymentHelper.class);
    private static final String STATUS = "status";
    private static final String MESSAGE = "message";
    private static final String TRANSACTION_JDO = "transactionJdo";
    private static final String SOURCE = "source";
    private static final String SOURCE_STRIPE = "STRIPE";

    private final PlatformIntentCache cache;
    private final ProcessPaymentIntentCommandHandler processPaymentIntentCommandHandler;
    private final ProcessSetupIntentCommandHandler processSetupIntentCommandHandler;
    private final StripePlatformCustomerRepository customerRepository;
    private final TransactionJDORepository transactionJDORepository;
    private final ProcessPaymentCommandHandler processPaymentCommandHandler;
    private final StripePlatformCustomerService platformCustomerService;
    private final RefundSubscriptionPaymentCommandHandler refundHandler;
    private final CapturePaymentIntentCommandHandler captureHandler;

    public StripeBraintreePaymentHelper(
            PlatformIntentCache cache,
            ProcessPaymentIntentCommandHandler processPaymentIntentCommandHandler,
            ProcessSetupIntentCommandHandler processSetupIntentCommandHandler,
            StripePlatformCustomerRepository customerRepository,
            TransactionJDORepository transactionJDORepository,
            ProcessPaymentCommandHandler processPaymentCommandHandler,
            StripePlatformCustomerService platformCustomerService,
            RefundSubscriptionPaymentCommandHandler refundHandler,
            CapturePaymentIntentCommandHandler captureHandler) {
        this.cache = cache;
        this.processPaymentIntentCommandHandler = processPaymentIntentCommandHandler;
        this.processSetupIntentCommandHandler = processSetupIntentCommandHandler;
        this.customerRepository = customerRepository;
        this.transactionJDORepository = transactionJDORepository;
        this.processPaymentCommandHandler = processPaymentCommandHandler;
        this.platformCustomerService = platformCustomerService;
        this.refundHandler = refundHandler;
        this.captureHandler = captureHandler;
    }

    public Map<String, Object> transactionStatus(String transactionId) {
        Optional<TransactionJDO> transaction = transactionJDORepository.findFirstByTransactionId(transactionId);
        if (transaction.isPresent() && isStripeTransaction(transaction.get())) {
            Map<String, Object> result = new HashMap<>();
            result.put(STATUS, true);
            // Real code returns responseStatus (null for charges). The spike also returns responseCode.
            result.put("transStatus", transaction.get().getResponseStatus());
            result.put("responseCode", transaction.get().getResponseCode());
            return result;
        }
        return braintreeNotAvailable("transactionStatus");
    }

    public Map<String, Object> refundTransaction(String transactionId, String amount) {
        Optional<TransactionJDO> transaction = transactionJDORepository.findFirstByTransactionId(transactionId);
        if (transaction.isPresent() && isStripeTransaction(transaction.get())) {
            try {
                TransactionJDO refund = refundHandler.refundSubscriptionPayment(transaction.get(), amount);
                Map<String, Object> result = new HashMap<>();
                result.put(STATUS, true);
                result.put(TRANSACTION_JDO, mapTransactionJdo(refund));
                return result;
            } catch (Exception e) {
                log.error("Refund failed", e);
                return errorResponse(e);
            }
        }
        return braintreeNotAvailable("refundTransaction");
    }

    public Map<String, Object> deleteVault(String customerKey) {
        return customerRepository
                .findByExternalId(customerKey)
                .map(customer -> {
                    platformCustomerService.detachAllPaymentMethods(customer);
                    Map<String, Object> result = new HashMap<>();
                    result.put(STATUS, true);
                    result.put(MESSAGE, "Details has been removed for the requested customer key");
                    return result;
                })
                .orElseGet(() -> braintreeNotAvailable("deleteVault"));
    }

    /** /processPayment/{type}/{customerKey}/{businessId}/{amount}/{productKey} – charge the saved payment method. */
    public Map<String, Object> processPayment(String customerKey, String amount, String productKey) {
        return getStripeCustomerWithVaultedCard(customerKey)
                .map(customer -> processPayment(amount, productKey, customer))
                .orElseGet(() -> braintreeNotAvailable(
                        "processPayment (no Stripe platform customer with a saved payment method for customerKey "
                                + customerKey + ")"));
    }

    /** /createTransaction/{businessId}/{amount}/{nonce}/{productKey} – nonce = platform PaymentIntent id. */
    public Map<String, Object> createTransactionWithNonce(String amount, String paymentMethodNonce, String productKey) {
        Optional<PlatformIntentCache.PlatformPaymentIntentMetadata> metadata = cache.getPaymentIntent(paymentMethodNonce);
        if (metadata.isPresent()) {
            try {
                CapturePaymentIntentCommandHandler.CaptureAndSaveResult result =
                        captureHandler.captureAndSave(metadata.get(), amount, productKey);
                Map<String, Object> response = new HashMap<>();
                response.put(STATUS, true);
                response.put(TRANSACTION_JDO, mapTransactionJdo(result.transactionJDO()));
                response.put("cardInfo", result.cardInfo());
                response.put(SOURCE, SOURCE_STRIPE);
                return response;
            } catch (Exception e) {
                log.error("Exception thrown during platform payment intent capture", e);
                return errorResponse(e);
            }
        }
        return braintreeNotAvailable("createTransaction (nonce " + paymentMethodNonce + " is not a known platform PI)");
    }

    /** /createVaultWithNonce/{businessId}/{customerKey}/{nonce} – nonce = pi_... or seti_... */
    public Map<String, Object> createCustomerWithNonce(
            String customerKey, String nonce, String productKey, Map<String, Object> customerJson) {
        return tryVaultWithStripe(nonce, customerJson, customerKey, productKey)
                .orElseGet(() -> braintreeNotAvailable("createVaultWithNonce (nonce " + nonce + " is unknown)"));
    }

    /** /v1/createVaultWithNonce/{businessId} – customerKey, nonce, productKey in the body. */
    public Map<String, Object> createCustomerWithNonceV1(Map<String, Object> customerJson) {
        String customerKey = String.valueOf(customerJson.get("customerKey"));
        String nonce = String.valueOf(customerJson.get("nonce"));
        String productKey = String.valueOf(customerJson.get("productKey"));
        return createCustomerWithNonce(customerKey, nonce, productKey, customerJson);
    }

    private Optional<Map<String, Object>> tryVaultWithStripe(
            String nonce, Map<String, Object> customerJson, String customerKey, String productKey) {
        var paymentIntent = cache.getPaymentIntent(nonce);
        if (paymentIntent.isPresent()) {
            try {
                return Optional.of(successResponse(processPaymentIntentCommandHandler.vaultWithPaymentIntent(
                        paymentIntent.get(), customerKey, productKey, billingDetails(customerJson))));
            } catch (Exception e) {
                log.error("Exception thrown during signup processing", e);
                return Optional.of(errorResponse(e));
            }
        }
        var setupIntent = cache.getSetupIntent(nonce);
        if (setupIntent.isPresent()) {
            try {
                return Optional.of(successResponse(processSetupIntentCommandHandler.processSetupIntent(
                        setupIntent.get(), customerKey, productKey, billingDetails(customerJson))));
            } catch (Exception e) {
                log.error("Exception thrown during setup intent processing", e);
                return Optional.of(errorResponse(e));
            }
        }
        return Optional.empty();
    }

    /** Returns the customer only if it has at least one vaulted payment method (same as fullpayments). */
    private Optional<StripePlatformCustomer> getStripeCustomerWithVaultedCard(String customerKey) {
        return customerRepository
                .findByExternalId(customerKey)
                .filter(c -> c.getPaymentMethodIds() != null && !c.getPaymentMethodIds().isEmpty());
    }

    private Map<String, Object> processPayment(String amount, String productKey, StripePlatformCustomer customer) {
        try {
            TransactionJDO jdo = processPaymentCommandHandler.processPayment(customer, amount, productKey);
            Map<String, Object> result = new HashMap<>();
            result.put(TRANSACTION_JDO, mapTransactionJdo(jdo));
            result.put(STATUS, true);
            result.put(SOURCE, SOURCE_STRIPE);
            return result;
        } catch (Exception e) {
            log.error("Exception thrown during payment processing", e);
            return errorResponse(e);
        }
    }

    private static boolean isStripeTransaction(TransactionJDO transaction) {
        // Stripe transactions always use the format 'pi_*****'
        return transaction.getTransactionId() != null && transaction.getTransactionId().startsWith("pi_");
    }

    private static Map<String, Object> successResponse(VaultingHelper.VaultingResult result) {
        Map<String, Object> response = new HashMap<>();
        response.put(STATUS, true);
        response.put("cardInfo", result.cardInfo());
        response.put("postalCode", result.postalCode());
        response.put(TRANSACTION_JDO, Map.of("customerKey", result.customerKey()));
        response.put(SOURCE, SOURCE_STRIPE);
        return response;
    }

    static Map<String, Object> mapTransactionJdo(TransactionJDO jdo) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("transactionId", jdo.getTransactionId());
        map.put("amount", jdo.getAmount());
        map.put("currency", jdo.getCurrency());
        map.put("responseCode", jdo.getResponseCode());
        map.put("responseStatus", jdo.getResponseStatus());
        map.put("customerKey", jdo.getCustomerKey());
        map.put("profileId", jdo.getProfileId());
        map.put("referenceTransaction", jdo.getReferenceTransaction());
        return map;
    }

    private static Map<String, Object> errorResponse(Exception e) {
        Map<String, Object> result = new HashMap<>();
        result.put(STATUS, false);
        result.put(MESSAGE, e.getMessage());
        return result;
    }

    private static Map<String, Object> braintreeNotAvailable(String operation) {
        Map<String, Object> result = new HashMap<>();
        result.put(STATUS, false);
        result.put(MESSAGE, "Not a Stripe customer/transaction – the real code would fall back to Braintree ("
                + operation + "). Braintree is not part of this spike.");
        return result;
    }

    private static StripePlatformCustomerService.BillingDetails billingDetails(Map<String, Object> json) {
        if (json == null) {
            return null;
        }
        return new StripePlatformCustomerService.BillingDetails(
                str(json.get("firstName")),
                str(json.get("lastName")),
                str(json.get("country")),
                str(json.get("postalCode")),
                str(json.get("emailId")));
    }

    private static String str(Object value) {
        return value == null ? null : Objects.toString(value);
    }
}
