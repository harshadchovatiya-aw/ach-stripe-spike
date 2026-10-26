package com.anywhere.spike.achstripe.fullpayments.facade;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionJDORepository extends JpaRepository<TransactionJDO, Long> {

    Optional<TransactionJDO> findFirstByTransactionId(String transactionId);

    List<TransactionJDO> findAllByOrderByIdDesc();
}
