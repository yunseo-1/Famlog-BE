package com.famlog.place.domain;

import java.time.Instant;

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

/** 개인위치정보 이용 확인자료 (사양서 8.6): 누가·언제·어떤 기능. 좌표는 저장하지 않는다. */
@Getter
@Entity
@Table(name = "location_access_logs")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LocationAccessLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private LocationPurpose purpose;

    @Column(name = "accessed_at", nullable = false, updatable = false)
    private Instant accessedAt;

    public LocationAccessLog(long userId, LocationPurpose purpose, Instant accessedAt) {
        this.userId = userId;
        this.purpose = purpose;
        this.accessedAt = accessedAt;
    }
}
