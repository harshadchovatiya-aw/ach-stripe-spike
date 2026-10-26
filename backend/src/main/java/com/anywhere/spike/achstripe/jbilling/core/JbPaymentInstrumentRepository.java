package com.anywhere.spike.achstripe.jbilling.core;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JbPaymentInstrumentRepository extends JpaRepository<JbPaymentInstrument, Integer> {
    List<JbPaymentInstrument> findByUserIdAndActiveTrueOrderByIdDesc(Integer userId);
}
