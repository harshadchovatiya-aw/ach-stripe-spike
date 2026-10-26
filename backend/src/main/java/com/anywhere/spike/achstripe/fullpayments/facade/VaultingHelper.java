package com.anywhere.spike.achstripe.fullpayments.facade;

import com.anywhere.spike.achstripe.fullpayments.customer.StripePlatformCustomerService;
import com.stripe.model.Customer;
import com.stripe.model.PaymentMethod;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Copy of fullpayments VaultingHelper + VaultingResult. */
@Component
public class VaultingHelper {

    private final TransactionJDORepository transactionJDORepository;

    public VaultingHelper(TransactionJDORepository transactionJDORepository) {
        this.transactionJDORepository = transactionJDORepository;
    }

    public record VaultingResult(CardInfo cardInfo, String country, String postalCode, String customerKey) {}

    /** Saves a "PROFILE" TransactionJDO, like the Braintree vault record jBilling used to expect. */
    public void saveTransactionJdoForBackwardCompatibility(
            Customer stripeCustomer, String jBillingCustomerKey, String productKey, String businessId) {
        TransactionJDO jdo = new TransactionJDO();
        jdo.setProfileId(stripeCustomer.getId());
        jdo.setSourceType("braintree");
        jdo.setProductKey(productKey != null ? productKey : stripeCustomer.getId());
        jdo.setBusinessId(businessId);
        jdo.setCustomerKey(jBillingCustomerKey);
        jdo.setLastModifiedDate(Instant.now());
        jdo.setResponseCode("PROFILE");
        transactionJDORepository.save(jdo);
    }

    public VaultingResult buildVaultingResult(
            PaymentMethod paymentMethod,
            StripePlatformCustomerService.BillingDetails billingDetails,
            String jBillingCustomerKey) {
        return new VaultingResult(
                CardInfo.extractCardInfo(paymentMethod),
                Optional.ofNullable(billingDetails).map(StripePlatformCustomerService.BillingDetails::country).orElse(null),
                Optional.ofNullable(billingDetails).map(StripePlatformCustomerService.BillingDetails::postalCode).orElse(null),
                jBillingCustomerKey);
    }
}
