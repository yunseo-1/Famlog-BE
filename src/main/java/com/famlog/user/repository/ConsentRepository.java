package com.famlog.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.famlog.user.domain.Consent;

public interface ConsentRepository extends JpaRepository<Consent, Long> {
}
