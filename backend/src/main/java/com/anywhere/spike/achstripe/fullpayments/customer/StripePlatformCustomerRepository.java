package com.anywhere.spike.achstripe.fullpayments.customer;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StripePlatformCustomerRepository extends JpaRepository<StripePlatformCustomer, String> {

    Optional<StripePlatformCustomer> findByExternalId(String externalId);

    Optional<StripePlatformCustomer> findByStripeCustomerId(String stripeCustomerId);
}
