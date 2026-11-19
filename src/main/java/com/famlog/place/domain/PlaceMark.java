package com.famlog.place.domain;

import java.io.Serializable;

import com.famlog.common.domain.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 구성원의 다녀옴·가고 싶어요 표시 (사양서 4.4 PLC-05). 장소를 지우면 함께 지워진다 (FK CASCADE). */
@Getter
@Entity
@Table(name = "place_marks")
@IdClass(PlaceMark.Key.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlaceMark extends BaseTimeEntity {

    @Id
    @Column(name = "place_id")
    private Long placeId;

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Id
    @Enumerated(EnumType.STRING)
    private PlaceMarkKind kind;

    public PlaceMark(long placeId, long userId, PlaceMarkKind kind) {
        this.placeId = placeId;
        this.userId = userId;
        this.kind = kind;
    }

    public record Key(Long placeId, Long userId, PlaceMarkKind kind) implements Serializable {
    }
}
