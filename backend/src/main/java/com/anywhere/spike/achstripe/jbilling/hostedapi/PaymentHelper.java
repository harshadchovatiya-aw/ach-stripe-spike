package com.anywhere.spike.achstripe.jbilling.hostedapi;

import com.anywhere.spike.achstripe.common.BadRequestException;
import com.anywhere.spike.achstripe.common.SpikeProperties;
import com.anywhere.spike.achstripe.jbilling.FullPaymentsClient;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** The parts of jBilling_hosted_api com.payment.PaymentHelper that call FullPayments for Stripe cards. */
@Component
public class PaymentHelper {

    public static final String PROVIDER_STRIPE = "STRIPE";

    private final FullPaymentsClient fullPaymentsClient;
    private final SpikeProperties properties;

    public PaymentHelper(FullPaymentsClient fullPaymentsClient, SpikeProperties properties) {
        this.fullPaymentsClient = fullPaymentsClient;
        this.properties = properties;
    }

    /**
     * PaymentHelper.getPaymentMethodDetails – GET stripe/v4/platform/payment-methods/{id} with
     * X-Business-ID = {brandId}.stripe.businessid and externalId = customerKey.
     * Throws when cardDetails is missing (same as the real getPaymentDetails).
     * ACH (hosted-API change): branch on type=us_bank_account and map bankAccountDetails.
     */
    @SuppressWarnings("unchecked")
    public Map<String, String> getPaymentMethodDetails(String paymentMethodId, String brandId, String customerKey) {
        String stripeBusinessId = properties.brand(brandId).stripeBusinessId();
        Map<String, Object> response = fullPaymentsClient.getPaymentMethod(stripeBusinessId, paymentMethodId, customerKey);
        if (Boolean.FALSE.equals(response.get("status"))) {
            throw new BadRequestException(String.valueOf(response.get("message")));
        }
        Object cardObject = response.get("cardDetails");
        if (!(cardObject instanceof Map<?, ?>)) {
            throw new BadRequestException("CardDetails missing for paymentMethodId=" + paymentMethodId
                    + " (type " + response.get("type") + ")");
        }
        Map<String, Object> card = (Map<String, Object>) cardObject;
        Map<String, String> details = new HashMap<>();
        details.put("cardNumber", "************" + card.get("last4"));
        details.put("expDate", card.get("expMonth") + "/" + card.get("expYear"));
        details.put("cardType", String.valueOf(card.get("brand")));
        details.put("provider", PROVIDER_STRIPE);
        details.put("btCustomerId", customerKey);
        return details;
    }

    /**
     * PaymentHelper.createVaultIdByNonce – POST {bt.createVaultIdByNonce.url}{btBusinessId}/{customerKey}/{nonce}.
     * For Stripe the nonce is a pi_... (pay + save) or seti_... (signup setup intent).
     */
    @SuppressWarnings("unchecked")
    public Map<String, String> createVaultIdByNonce(
            String brandId, String nonce, String customerKey, Map<String, String> billingAddress, String userId) {
        String btBusinessId = properties.brand(brandId).btBusinessId();
        Map<String, Object> address = new HashMap<>();
        if (billingAddress != null) {
            address.put("firstName", billingAddress.get("firstName"));
            address.put("lastName", billingAddress.get("lastName"));
            address.put("postalCode", billingAddress.get("zip"));
            address.put("country", billingAddress.get("country"));
            address.put("emailId", billingAddress.get("email"));
        }
        address.put("productKey", userId);

        Map<String, Object> response = fullPaymentsClient.createVaultWithNonce(btBusinessId, customerKey, nonce, address);
        if (!Boolean.TRUE.equals(response.get("status"))) {
            throw new BadRequestException("RespFull: " + response.get("message"));
        }
        Map<String, String> details = new HashMap<>();
        Object cardInfoObject = response.get("cardInfo");
        if (cardInfoObject instanceof Map<?, ?> map) {
            Map<String, Object> cardInfo = (Map<String, Object>) map;
            details.put("cardNumber", "************" + cardInfo.get("last4"));
            details.put("expDate", String.valueOf(cardInfo.get("expirationDate")));
            details.put("cardType", String.valueOf(cardInfo.get("type")));
        }
        details.put("btCustomerId", customerKey);
        details.put("provider", String.valueOf(response.getOrDefault("source", "BRAINTREE")));
        return details;
    }

    /** UserUtilities.updateCustomerKeyInFullPayments – PATCH stripe.platform.customer.update?externalId= */
    public void updateCustomerKeyInFullPayments(String customerKey, String accountPin) {
        fullPaymentsClient.updatePlatformCustomer(customerKey, accountPin);
    }
}
