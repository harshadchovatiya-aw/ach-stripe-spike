package com.anywhere.spike.achstripe;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * One app that contains two "systems", kept in separate packages so they can be split later:
 *
 * <ul>
 *   <li>{@code fullpayments} – a copy of the FullPayments Stripe <b>platform</b> flow (platform setup/payment
 *       intents, payment-method lookup, platform customers, the "Braintree facade" used by jBilling, the platform
 *       webhook).</li>
 *   <li>{@code jbilling} – a copy of the jBilling hosted API endpoints the UI calls (/processPayment,
 *       /updatePaymentInstrument, /v1/updatePaymentInstrument, /getPaymentInstruments) plus a small simulated
 *       jBilling core (users, payment instruments, payments, payment-task plugin).</li>
 * </ul>
 *
 * The jbilling part calls the fullpayments part over HTTP, like the real systems do.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AchStripeSpikeApplication {

    public static void main(String[] args) {
        SpringApplication.run(AchStripeSpikeApplication.class, args);
    }
}
