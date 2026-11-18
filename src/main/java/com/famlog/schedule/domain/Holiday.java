package com.famlog.schedule.domain;

import java.time.LocalDate;

import com.famlog.common.domain.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 대한민국 공휴일·대체공휴일 (사양서 4.3 SCH-04, 10.2 holidays). 데이터는 마이그레이션으로 넣는다. */
@Getter
@Entity
@Table(name = "holidays")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Holiday extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false)
    private String name;

    @Column(name = "is_substitute", nullable = false)
    private boolean substitute;
}
