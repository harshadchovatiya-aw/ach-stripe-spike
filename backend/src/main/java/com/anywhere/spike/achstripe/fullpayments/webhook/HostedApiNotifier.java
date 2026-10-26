package com.anywhere.spike.achstripe.fullpayments.webhook;

import com.anywhere.spike.achstripe.common.SpikeProperties;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * ACH (P8): FullPayments → jBilling hosted API callback (POST {hostedApi}/stripe/ach/payment-status).
 * In production this could also be a Pub/Sub message; the payload is the same.
 */
@Component
public class HostedApiNotifier {

    private static final Logger log = LoggerFactory.getLogger(HostedApiNotifier.class);

    public enum AchEvent {
        PAYMENT_SUCCEEDED,
        PAYMENT_FAILED,
        DISPUTE_CREATED,
        MANDATE_INACTIVE,
        REFUND_FAILED
    }

    public record AchPaymentStatusNotification(
            AchEvent event,
            String eventId,
            String paymentIntentId,
            String accountPin,
            String customerKey,
            String amount,
            String currency,
            String paymentMethodType,
            String failureCode,
            String failureMessage,
            Instant occurredAt) {}

    private final RestClient restClient;

    public HostedApiNotifier(SpikeProperties properties) {
        this.restClient = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()))
                .baseUrl(properties.hostedApiBaseUrl())
                .build();
    }

    public String notify(AchPaymentStatusNotification notification) {
        try {
            Map<?, ?> response = restClient.post()
                    .uri("/stripe/ach/payment-status")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(notification)
                    .retrieve()
                    .body(Map.class);
            log.info("Notified hosted API of {} for {}: {}", notification.event(), notification.paymentIntentId(), response);
            return "hosted API notified (" + notification.event() + "): " + (response != null ? response.get("message") : "");
        } catch (Exception e) {
            log.warn("Hosted API notification failed for {}: {}", notification.paymentIntentId(), e.getMessage());
            return "hosted API notification FAILED: " + e.getMessage();
        }
    }
}
