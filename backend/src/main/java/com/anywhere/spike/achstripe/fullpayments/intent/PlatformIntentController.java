package com.anywhere.spike.achstripe.fullpayments.intent;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** FullPayments platform intent endpoints called directly by the UI. */
@RestController
public class PlatformIntentController {

    private final CreateSetupIntentCommandHandler setupIntentHandler;
    private final PlatformPaymentIntentHandlers paymentIntentHandlers;

    public PlatformIntentController(
            CreateSetupIntentCommandHandler setupIntentHandler, PlatformPaymentIntentHandlers paymentIntentHandlers) {
        this.setupIntentHandler = setupIntentHandler;
        this.paymentIntentHandlers = paymentIntentHandlers;
    }

    /** operationId createPlatformSetupIntentV4 */
    @PostMapping("/api/stripe/v4/platform/setup-intents")
    public CreateSetupIntentCommandHandler.CreateSetupIntentResponse createPlatformSetupIntent(
            @RequestHeader("X-Business-ID") String businessId, @RequestParam(required = false) String customerKey) {
        return setupIntentHandler.handle(businessId, customerKey);
    }

    /** operationId createPlatformPaymentIntentV4 */
    @PostMapping("/api/stripe/v4/online-checkout/platform/payment-intents")
    public PlatformPaymentIntentHandlers.CreatePlatformPaymentIntentResponse createPlatformPaymentIntent(
            @RequestHeader("X-Business-ID") String businessId,
            @RequestParam(required = false) String customerKey,
            @RequestBody PlatformPaymentIntentHandlers.CreatePlatformPaymentIntentRequest request) {
        return paymentIntentHandlers.create(request, businessId, customerKey);
    }

    /** operationId updatePlatformPaymentIntentV4 */
    @PatchMapping("/api/stripe/v4/online-checkout/platform/payment-intents/{paymentIntentId}")
    public ResponseEntity<Map<String, Object>> updatePlatformPaymentIntent(
            @RequestHeader("X-Business-ID") String businessId,
            @PathVariable String paymentIntentId,
            @RequestBody PlatformPaymentIntentHandlers.UpdatePlatformPaymentIntentRequest request) {
        paymentIntentHandlers.update(request, businessId, paymentIntentId);
        return ResponseEntity.ok(Map.of("success", true));
    }
}
