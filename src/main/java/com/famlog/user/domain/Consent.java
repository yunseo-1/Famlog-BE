package com.famlog.user.domain;

import java.time.Instant;

import com.famlog.common.domain.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 동의 이력 (사양서 8.3). 철회해도 지우지 않고 withdrawnAt을 채운다 (동의 증빙). */
@Getter
@Entity
@Table(name = "consents")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Consent extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private ConsentType type;

    @Column(name = "doc_version", nullable = false, updatable = false)
    private String docVersion;

    @Column(name = "agreed_at", nullable = false, updatable = false)
    private Instant agreedAt;

    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;

    public Consent(Long userId, ConsentType type, String docVersion, Instant agreedAt) {
        this.userId = userId;
        this.type = type;
        this.docVersion = docVersion;
        this.agreedAt = agreedAt;
    }
}
