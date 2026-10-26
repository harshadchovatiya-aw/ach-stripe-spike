package com.anywhere.spike.achstripe.jbilling.core;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JbUserRepository extends JpaRepository<JbUser, Integer> {
    Optional<JbUser> findByAccountPin(String accountPin);
}
