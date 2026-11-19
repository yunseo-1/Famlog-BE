package com.famlog.place.domain;

import com.famlog.common.domain.BaseTimeEntity;
import com.famlog.group.domain.GroupRole;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 별칭 장소 (사양서 4.4 PLC-06): "할머니 댁"처럼 검색이 안 되는 곳. 공유한 장소를 연결하거나 주소를 적어 둘 수 있다 (둘 다 선택).
 * 연결한 장소를 지우면 연결만 비워진다 (FK SET NULL).
 */
@Getter
@Entity
@Table(name = "place_aliases")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlaceAlias extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false, updatable = false)
    private Long groupId;

    @Column(nullable = false, updatable = false)
    private String alias;

    @Column(name = "place_id")
    private Long placeId;

    @Column(name = "address_text")
    private String addressText;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    public PlaceAlias(long groupId, long createdBy, String alias, Long placeId, String addressText) {
        this.groupId = groupId;
        this.createdBy = createdBy;
        this.alias = alias;
        this.placeId = placeId;
        this.addressText = addressText;
    }

    /** 사양서 2.2: 남이 만든 별칭은 ADMIN 이상만 지운다 */
    public boolean isDeletableBy(long userId, GroupRole role) {
        return createdBy == userId || role.atLeast(GroupRole.ADMIN);
    }
}
