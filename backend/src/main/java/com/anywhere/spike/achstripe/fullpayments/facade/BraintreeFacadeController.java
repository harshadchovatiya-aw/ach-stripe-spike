package com.anywhere.spike.achstripe.fullpayments.facade;

import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The subset of fullpayments com.acti.braintree.controller.BraintreeController (/api/braintree/*) that is
 * routed to Stripe by StripeBraintreePaymentHelper. These are the endpoints jBilling (core plugin + hosted API)
 * calls. Path shapes are the same as the real controller.
 */
@RestController
@RequestMapping("/api/braintree")
public class BraintreeFacadeController {

    private final StripeBraintreePaymentHelper helper;

    public BraintreeFacadeController(StripeBraintreePaymentHelper helper) {
        this.helper = helper;
    }

    @PostMapping("/createVaultWithNonce/{businessId}/{customerKey}/{nonce}")
    public Map<String, Object> createVaultWithNonce(
            @PathVariable String businessId,
            @PathVariable String customerKey,
            @PathVariable String nonce,
            @RequestParam(required = false) String productKey,
            @RequestParam(required = false) String deviceData,
            @RequestBody Map<String, Object> customerJson) {
        String effectiveProductKey = productKey != null ? productKey : (String) customerJson.get("productKey");
        return helper.createCustomerWithNonce(customerKey, nonce, effectiveProductKey, customerJson);
    }

    @PostMapping("/v1/createVaultWithNonce/{businessId}")
    public Map<String, Object> createVaultWithNonceV1(
            @PathVariable String businessId, @RequestBody Map<String, Object> customerJson) {
        return helper.createCustomerWithNonceV1(customerJson);
    }

    @PostMapping("/createTransaction/{businessId}/{amount}/{paymentMethodNonce}/{productKey}")
    public Map<String, Object> createTransaction(
            @PathVariable String businessId,
            @PathVariable String amount,
            @PathVariable String paymentMethodNonce,
            @PathVariable String productKey) {
        return helper.createTransactionWithNonce(amount, paymentMethodNonce, productKey);
    }

    @PostMapping("/processPayment/{type}/{customerKey}/{businessId}/{amount}/{productKey}")
    public Map<String, Object> processPayment(
            @PathVariable String type,
            @PathVariable String customerKey,
            @PathVariable String businessId,
            @PathVariable String amount,
            @PathVariable String productKey) {
        return helper.processPayment(customerKey, amount, productKey);
    }

    @PostMapping("/createTransactionWithCustomerKey/{businessId}/{customerKey}/{amount}/{isAuthorization}/{productKey}")
    public Map<String, Object> createTransactionWithCustomerKey(
            @PathVariable String businessId,
            @PathVariable String customerKey,
            @PathVariable String amount,
            @PathVariable Boolean isAuthorization,
            @PathVariable String productKey) {
        return helper.processPayment(customerKey, amount, productKey);
    }

    @RequestMapping(
            value = "/refundTransaction/{businessId}/{transactionId}/{amount}",
            method = {RequestMethod.GET, RequestMethod.POST})
    public Map<String, Object> refundTransaction(
            @PathVariable String businessId, @PathVariable String transactionId, @PathVariable String amount) {
        return helper.refundTransaction(transactionId, amount);
    }

    @GetMapping("/transactionStatus/{businessId}/{transactionId}")
    public Map<String, Object> transactionStatus(@PathVariable String businessId, @PathVariable String transactionId) {
        return helper.transactionStatus(transactionId);
    }

    @DeleteMapping("/deleteVault/{customerKey}/{businessId}")
    public Map<String, Object> deleteVault(@PathVariable String customerKey, @PathVariable String businessId) {
        return helper.deleteVault(customerKey);
    }
}
