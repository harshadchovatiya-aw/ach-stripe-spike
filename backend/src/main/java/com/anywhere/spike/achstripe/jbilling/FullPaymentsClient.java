package com.anywhere.spike.achstripe.jbilling;

import com.anywhere.spike.achstripe.common.SpikeProperties;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * HTTP client the jBilling side (hosted API + simulated core plugin) uses to call FullPayments – the same
 * boundary as in production (URLFetch / service-account bearer token there; no auth in the spike).
 */
@Component
public class FullPaymentsClient {

    private static final Logger log = LoggerFactory.getLogger(FullPaymentsClient.class);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};

    private final RestClient restClient;

    public FullPaymentsClient(SpikeProperties properties) {
        // JDK HttpClient: supports PATCH (HttpURLConnection does not)
        this.restClient = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
                        .version(java.net.http.HttpClient.Version.HTTP_1_1)
                        .build()))
                .baseUrl(properties.fullpaymentsBaseUrl())
                .build();
    }

    // ---- Stripe v4 platform API ----

    /** PaymentHelper.getPaymentMethodDetails → GET stripe/v4/platform/payment-methods/{id}?externalId= */
    public Map<String, Object> getPaymentMethod(String stripeBusinessId, String paymentMethodId, String customerKey) {
        return call("GET payment-methods/" + paymentMethodId, () -> restClient.get()
                .uri(b -> b.path("/api/stripe/v4/platform/payment-methods/{id}")
                        .queryParamIfPresent("externalId", java.util.Optional.ofNullable(customerKey))
                        .build(paymentMethodId))
                .header("X-Business-ID", stripeBusinessId)
                .retrieve()
                .body(MAP));
    }

    /** updateCustomerKeyInFullPayments → PATCH stripe/v4/platform/customers?externalId= */
    public Map<String, Object> updatePlatformCustomer(String customerKey, String accountPin) {
        return call("PATCH platform/customers", () -> restClient.patch()
                .uri(b -> b.path("/api/stripe/v4/platform/customers").queryParam("externalId", customerKey).build())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("merchantId", accountPin))
                .retrieve()
                .body(MAP));
    }

    // ---- Braintree facade (/api/braintree/*) ----

    /** PaymentHelper.createVaultIdByNonce → POST /api/braintree/createVaultWithNonce/{businessId}/{customerKey}/{nonce} */
    public Map<String, Object> createVaultWithNonce(
            String btBusinessId, String customerKey, String nonce, Map<String, Object> customerJson) {
        return call("createVaultWithNonce", () -> restClient.post()
                .uri("/api/braintree/createVaultWithNonce/{businessId}/{customerKey}/{nonce}",
                        btBusinessId, customerKey, nonce)
                .contentType(MediaType.APPLICATION_JSON)
                .body(customerJson)
                .retrieve()
                .body(MAP));
    }

    /** jBilling plugin, single-use token → POST /api/braintree/createTransaction/{businessId}/{amount}/{nonce}/{productKey} */
    public Map<String, Object> createTransaction(String btBusinessId, String amount, String nonce, String productKey) {
        return call("createTransaction", () -> restClient.post()
                .uri("/api/braintree/createTransaction/{businessId}/{amount}/{nonce}/{productKey}",
                        btBusinessId, amount, nonce, productKey)
                .retrieve()
                .body(MAP));
    }

    /** jBilling plugin, saved instrument → POST /api/braintree/processPayment/{type}/{customerKey}/{businessId}/{amount}/{productKey} */
    public Map<String, Object> processPayment(
            String type, String customerKey, String btBusinessId, String amount, String productKey) {
        return call("processPayment", () -> restClient.post()
                .uri("/api/braintree/processPayment/{type}/{customerKey}/{businessId}/{amount}/{productKey}",
                        type, customerKey, btBusinessId, amount, productKey)
                .retrieve()
                .body(MAP));
    }

    /** jBilling refund → /api/braintree/refundTransaction/{businessId}/{transactionId}/{amount} */
    public Map<String, Object> refundTransaction(String btBusinessId, String transactionId, String amount) {
        return call("refundTransaction", () -> restClient.post()
                .uri("/api/braintree/refundTransaction/{businessId}/{transactionId}/{amount}",
                        btBusinessId, transactionId, amount)
                .retrieve()
                .body(MAP));
    }

    private Map<String, Object> call(String name, java.util.function.Supplier<Map<String, Object>> request) {
        try {
            Map<String, Object> response = request.get();
            log.debug("FullPayments {} -> {}", name, response);
            return response == null ? new HashMap<>() : response;
        } catch (RestClientResponseException e) {
            log.warn("FullPayments {} failed: {} {}", name, e.getStatusCode(), e.getResponseBodyAsString());
            Map<String, Object> error = new HashMap<>();
            error.put("status", false);
            error.put("message", "FullPayments " + name + " failed: " + e.getResponseBodyAsString());
            return error;
        }
    }
}
