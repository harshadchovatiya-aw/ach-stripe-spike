package com.anywhere.spike.achstripe.jbilling.core;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JbPaymentRepository extends JpaRepository<JbPayment, Integer> {
    List<JbPayment> findByUserIdOrderByIdDesc(Integer userId);
}
