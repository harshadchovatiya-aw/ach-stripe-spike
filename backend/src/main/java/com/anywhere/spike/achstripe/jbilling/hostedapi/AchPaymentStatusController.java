package com.anywhere.spike.achstripe.jbilling.hostedapi;

import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * ACH: new jBilling hosted API endpoint called by FullPayments when an ACH payment settles, fails, is disputed, its
 * mandate becomes inactive, or a refund fails (implementation plan 6.4). In production: service-account bearer token.
 */
@RestController
public class AchPaymentStatusController {

    private final AchPaymentStatusService service;

    public AchPaymentStatusController(AchPaymentStatusService service) {
        this.service = service;
    }

    @PostMapping("/stripe/ach/payment-status")
    public Map<String, Object> paymentStatus(@RequestBody AchPaymentStatusService.AchPaymentStatus status) {
        return service.handle(status);
    }
}
