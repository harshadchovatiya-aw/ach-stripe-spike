package com.anywhere.spike.achstripe.spike;

import com.anywhere.spike.achstripe.common.BadRequestException;
import com.anywhere.spike.achstripe.common.Money;
import com.anywhere.spike.achstripe.common.SpikeProperties;
import com.anywhere.spike.achstripe.fullpayments.customer.StripePlatformCustomerRepository;
import com.anywhere.spike.achstripe.fullpayments.facade.TransactionJDORepository;
import com.anywhere.spike.achstripe.fullpayments.intent.PlatformIntentCache;
import com.anywhere.spike.achstripe.jbilling.core.DsActivity;
import com.anywhere.spike.achstripe.jbilling.core.DsActivityRepository;
import com.anywhere.spike.achstripe.jbilling.core.JBillingCoreService;
import com.anywhere.spike.achstripe.jbilling.core.JbPaymentRepository;
import com.anywhere.spike.achstripe.jbilling.core.JbUser;
import com.anywhere.spike.achstripe.jbilling.core.JbUserRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Spike-only helper endpoints (not part of the real systems): test accounts, a simulated billing run, refunds and
 * read-only views of what each system stored, so the UI can show the whole picture.
 */
@RestController
@RequestMapping("/spike")
public class SpikeController {

    private final SpikeProperties properties;
    private final JbUserRepository userRepository;
    private final JbPaymentRepository paymentRepository;
    private final DsActivityRepository dsActivityRepository;
    private final JBillingCoreService jBilling;
    private final StripePlatformCustomerRepository platformCustomerRepository;
    private final TransactionJDORepository transactionJDORepository;
    private final PlatformIntentCache intentCache;

    public SpikeController(
            SpikeProperties properties,
            JbUserRepository userRepository,
            JbPaymentRepository paymentRepository,
            DsActivityRepository dsActivityRepository,
            JBillingCoreService jBilling,
            StripePlatformCustomerRepository platformCustomerRepository,
            TransactionJDORepository transactionJDORepository,
            PlatformIntentCache intentCache) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.paymentRepository = paymentRepository;
        this.dsActivityRepository = dsActivityRepository;
        this.jBilling = jBilling;
        this.platformCustomerRepository = platformCustomerRepository;
        this.transactionJDORepository = transactionJDORepository;
        this.intentCache = intentCache;
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        List<Map<String, Object>> brands = new ArrayList<>();
        properties.brands().forEach((brandId, brand) -> {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("brandId", brandId);
            b.put("name", brand.name());
            b.put("stripeBusinessId", brand.stripeBusinessId());
            b.put("btBusinessId", brand.btBusinessId());
            b.put("currency", brand.currency());
            b.put("currencySymbol", brand.currencySymbol());
            boolean keys = properties.stripeAccount(brand.stripeBusinessId())
                    .map(a -> notBlank(a.secretKey()) && notBlank(a.publishableKey()))
                    .orElse(false);
            b.put("stripeKeysConfigured", keys);
            brands.add(b);
        });
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("brands", brands);
        res.put("webhookSecretConfigured",
                notBlank(properties.stripe().webhookSecret()) || notBlank(properties.stripe().webhookSecretFile()));
        return res;
    }

    public record CreateAccountRequest(
            String brandId, String email, String firstName, String lastName, String postalCode, String country,
            BigDecimal owingBalance) {}

    @PostMapping("/accounts")
    public JbUser createAccount(@RequestBody CreateAccountRequest request) {
        String brandId = Optional.ofNullable(request.brandId())
                .orElse(properties.brands().keySet().iterator().next());
        properties.brand(brandId);
        String pin = "AF-" + ThreadLocalRandom.current().nextInt(100000, 999999);
        return userRepository.save(new JbUser(
                pin,
                brandId,
                Optional.ofNullable(request.email()).orElse(pin.toLowerCase() + "@example.com"),
                Optional.ofNullable(request.firstName()).orElse("Test"),
                Optional.ofNullable(request.lastName()).orElse("Customer"),
                Optional.ofNullable(request.postalCode()).orElse("10001"),
                Optional.ofNullable(request.country()).orElse("US"),
                Optional.ofNullable(request.owingBalance()).orElse(new BigDecimal("49.00"))));
    }

    @GetMapping("/accounts")
    public List<JbUser> accounts() {
        return userRepository.findAll();
    }

    /** Everything about one account, from both "systems". */
    @GetMapping("/accounts/{accountPin}/overview")
    public Map<String, Object> overview(@PathVariable String accountPin) {
        JbUser user = jBilling.getUserByAccountPin(accountPin);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("user", user);
        res.put("instruments", jBilling.getPaymentInstruments(user.getId()));
        res.put("payments", paymentRepository.findByUserIdOrderByIdDesc(user.getId()));
        res.put("dsActivity", dsActivityRepository.findByAccountPinOrderByIdDesc(accountPin));
        return res;
    }

    /** Simulates the jBilling billing process charging the owing balance with the instrument on file (recurring). */
    @PostMapping("/accounts/{accountPin}/billing-run")
    public Map<String, Object> billingRun(@PathVariable String accountPin, @RequestBody(required = false) Map<String, Object> body) {
        JbUser user = jBilling.getUserByAccountPin(accountPin);
        BigDecimal amount = body != null && body.get("amount") != null
                ? Money.parse(String.valueOf(body.get("amount")))
                : user.getOwingBalance();
        if (amount.signum() <= 0) {
            throw new BadRequestException("Nothing to charge (owing balance is " + amount + ")");
        }
        var result = jBilling.processPayment(user, amount, null, "Billing run (recurring charge)");
        dsActivityRepository.save(new DsActivity(accountPin, DsActivity.NOTE, "Billing run",
                "Recurring charge " + amount + ": " + result.payment().getResult() + " "
                        + result.authorization().transactionId() + " " + result.authorization().responseMessage()));
        return Map.of("payment", result.payment(), "authorization", result.authorization());
    }

    @PostMapping("/payments/{paymentId}/refund")
    public Map<String, Object> refund(@PathVariable Integer paymentId, @RequestBody(required = false) Map<String, Object> body) {
        BigDecimal amount = body != null && body.get("amount") != null
                ? Money.parse(String.valueOf(body.get("amount")))
                : null;
        var result = jBilling.refund(paymentId, amount);
        return Map.of("payment", result.payment(), "authorization", result.authorization());
    }

    @GetMapping("/fullpayments")
    public Map<String, Object> fullPaymentsState() {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("platformCustomers", platformCustomerRepository.findAll());
        res.put("transactions", transactionJDORepository.findAllByOrderByIdDesc());
        res.put("caches", intentCache.snapshot());
        return res;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
