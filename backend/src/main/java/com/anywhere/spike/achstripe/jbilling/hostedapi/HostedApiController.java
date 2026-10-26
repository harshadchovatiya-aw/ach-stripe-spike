package com.anywhere.spike.achstripe.jbilling.hostedapi;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The jBilling_hosted_api endpoints (com.user.UserController) the payment UI calls. */
@RestController
public class HostedApiController {

    private final HostedApiService service;

    public HostedApiController(HostedApiService service) {
        this.service = service;
    }

    @PostMapping("/processPayment")
    public Map<String, Object> processPayment(@RequestBody Map<String, Object> request) {
        return service.processPayment(request);
    }

    /** updatePaymentInstrumentAndPayment */
    @PutMapping("/updatePaymentInstrument")
    public Map<String, Object> updatePaymentInstrumentAndPayment(@RequestBody Map<String, Object> request) {
        return service.updatePaymentInstrumentAndPayment(request);
    }

    /** updatePaymentInstrument */
    @PutMapping("/v1/updatePaymentInstrument")
    public Map<String, Object> updatePaymentInstrument(@RequestBody Map<String, Object> request) {
        return service.updatePaymentInstrument(request);
    }

    @GetMapping("/getPaymentInstruments")
    public Map<String, Object> getPaymentInstruments(@RequestParam String accountPin, @RequestParam String brandId) {
        return service.getPaymentInstruments(accountPin, brandId);
    }
}
