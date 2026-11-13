package com.famlog.user.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.famlog.user.domain.SocialAccount;
import com.famlog.user.domain.SocialProvider;

public interface SocialAccountRepository extends JpaRepository<SocialAccount, Long> {

    Optional<SocialAccount> findByProviderAndProviderUserId(SocialProvider provider, String providerUserId);

    boolean existsByProviderAndProviderUserId(SocialProvider provider, String providerUserId);
}
