package com.famlog.user.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.famlog.user.domain.Consent;
import com.famlog.user.domain.ConsentType;

public interface ConsentRepository extends JpaRepository<Consent, Long> {

    /** 지금 유효한(철회하지 않은) 동의가 있는지 */
    boolean existsByUserIdAndTypeAndWithdrawnAtIsNull(Long userId, ConsentType type);

    /** 지금 유효한 동의 전부 (종류마다 보통 하나) */
    List<Consent> findByUserIdAndWithdrawnAtIsNull(Long userId);

    List<Consent> findByUserIdAndTypeAndWithdrawnAtIsNull(Long userId, ConsentType type);
}
