package com.anywhere.spike.achstripe.jbilling.core;

import com.anywhere.spike.achstripe.common.SpikeProperties;
import com.anywhere.spike.achstripe.jbilling.FullPaymentsClient;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Simulates the jBilling core payment-task plugin bound to the hosted-card payment method types. The real plugin
 * lives in jBilling core (not in jBilling_hosted_api or fullpayments); from the FullPayments side we know it calls
 * the Braintree facade:
 * <ul>
 *   <li>single-use token (nonce)  → /api/braintree/createTransaction/{btBusinessId}/{amount}/{nonce}/{productKey}</li>
 *   <li>saved instrument ("BT ID") → /api/braintree/processPayment/{type}/{customerKey}/{btBusinessId}/{amount}/{productKey}</li>
 * </ul>
 * ACH: the new stripe-achId type is bound to this same plugin – FullPayments charges whatever payment method the
 * Stripe customer has, so no new jBilling integration is needed.
 */
@Component
public class FullPaymentsGatewayPlugin {

    public static final String META_BT_ID = "BT ID";

    private final FullPaymentsClient fullPaymentsClient;
    private final SpikeProperties properties;

    public FullPaymentsGatewayPlugin(FullPaymentsClient fullPaymentsClient, SpikeProperties properties) {
        this.fullPaymentsClient = fullPaymentsClient;
        this.properties = properties;
    }

    /**
     * PaymentAuthorizationDTOEx in jBilling. responseCode / paymentMethodType come from the facade's TransactionJDO
     * (ACH: PENDING = submitted to the bank, not settled yet).
     */
    public record PaymentAuthorization(
            boolean result,
            String transactionId,
            String responseMessage,
            String responseCode,
            String paymentMethodType,
            Map<String, Object> raw) {

        public boolean isPendingSettlement() {
            return "PENDING".equals(responseCode);
        }
    }

    public PaymentAuthorization charge(JbUser user, JbPaymentInstrument instrument, BigDecimal amount) {
        String btBusinessId = properties.brand(user.getBrandId()).btBusinessId();
        String productKey = String.valueOf(user.getId());
        String amountString = amount.toPlainString();

        Map<String, Object> response;
        if (instrument.getSingleUseToken() != null) {
            response = fullPaymentsClient.createTransaction(
                    btBusinessId, amountString, instrument.getSingleUseToken(), productKey);
        } else {
            String customerKey = instrument.getMetaFields().get(META_BT_ID);
            String type = JbPaymentInstrument.TEMPLATE_ACH.equals(instrument.getMethodTemplate()) ? "ach" : "cc";
            response = fullPaymentsClient.processPayment(type, customerKey, btBusinessId, amountString, productKey);
        }
        return toAuthorization(response);
    }

    public PaymentAuthorization refund(JbUser user, String transactionId, BigDecimal amount) {
        String btBusinessId = properties.brand(user.getBrandId()).btBusinessId();
        return toAuthorization(fullPaymentsClient.refundTransaction(btBusinessId, transactionId, amount.toPlainString()));
    }

    @SuppressWarnings("unchecked")
    private static PaymentAuthorization toAuthorization(Map<String, Object> response) {
        boolean ok = Boolean.TRUE.equals(response.get("status"));
        String transactionId = null;
        String responseCode = null;
        String paymentMethodType = null;
        Object jdo = response.get("transactionJdo");
        if (jdo instanceof Map<?, ?> map) {
            Map<String, Object> tx = (Map<String, Object>) map;
            transactionId = (String) tx.get("transactionId");
            responseCode = tx.get("responseCode") != null ? String.valueOf(tx.get("responseCode"))
                    : tx.get("responseStatus") != null ? String.valueOf(tx.get("responseStatus")) : null;
            paymentMethodType = (String) tx.get("paymentMethodType");
        }
        String message = ok ? "Approved" : String.valueOf(response.get("message"));
        return new PaymentAuthorization(ok, transactionId, message, responseCode, paymentMethodType, response);
    }
}
