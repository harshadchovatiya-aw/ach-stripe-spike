package com.anywhere.spike.achstripe.fullpayments.customer;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** FullPayments: PATCH /api/stripe/v4/platform/customers?externalId= (operationId updatePlatformCustomer). */
@RestController
public class PlatformCustomerController {

    private final StripePlatformCustomerService service;

    public PlatformCustomerController(StripePlatformCustomerService service) {
        this.service = service;
    }

    public record UpdatePlatformCustomerRequest(String merchantId) {}

    @PatchMapping("/api/stripe/v4/platform/customers")
    public ResponseEntity<Map<String, Object>> update(
            @RequestParam String externalId, @RequestBody UpdatePlatformCustomerRequest request) {
        service.updatePlatformCustomer(externalId, request.merchantId());
        return ResponseEntity.ok(Map.of("success", true));
    }
}
