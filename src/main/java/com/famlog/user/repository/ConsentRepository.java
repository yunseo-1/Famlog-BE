package com.famlog.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.famlog.user.domain.Consent;
import com.famlog.user.domain.ConsentType;

public interface ConsentRepository extends JpaRepository<Consent, Long> {

    /** 지금 유효한(철회하지 않은) 동의가 있는지 */
    boolean existsByUserIdAndTypeAndWithdrawnAtIsNull(Long userId, ConsentType type);
}
