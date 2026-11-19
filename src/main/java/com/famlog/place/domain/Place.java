package com.famlog.place.domain;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.famlog.common.domain.BaseTimeEntity;
import com.famlog.group.domain.GroupRole;

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

/**
 * 그룹에 공유한 장소 (사양서 4.4 PLC-01, 10.2 places). 이름·주소·좌표는 카카오 로컬 검색 결과 그대로이고,
 * 카테고리·한줄평·별점만 고칠 수 있다. 거리 검색용 geom 은 DB 가 lat·lng 로 만든다 (V8).
 */
@Getter
@Entity
@Table(name = "places")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Place extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false, updatable = false)
    private Long groupId;

    @Column(name = "kakao_place_id", nullable = false, updatable = false)
    private String kakaoPlaceId;

    @Column(nullable = false, updatable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlaceCategory category;

    @Column(name = "category_detail", updatable = false)
    private String categoryDetail;

    @Column(nullable = false, updatable = false)
    private String address;

    @Column(nullable = false, updatable = false)
    private String region1;

    @Column(updatable = false)
    private String region2;

    @Column(updatable = false)
    private String region3;

    @Column(nullable = false, updatable = false)
    private double lat;

    @Column(nullable = false, updatable = false)
    private double lng;

    @Column(updatable = false)
    private String phone;

    @Column(name = "place_url", updatable = false)
    private String placeUrl;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    private Integer rating;

    @Column(name = "one_line_review")
    private String oneLineReview;

    @Column(name = "shared_by", nullable = false, updatable = false)
    private Long sharedBy;

    public Place(long groupId, long sharedBy, KakaoInfo info, PlaceCategory category, Integer rating,
            String oneLineReview) {
        this.groupId = groupId;
        this.sharedBy = sharedBy;
        this.kakaoPlaceId = info.kakaoPlaceId();
        this.name = info.name();
        this.categoryDetail = info.categoryDetail();
        this.address = info.address();
        this.region1 = info.region1();
        this.region2 = info.region2();
        this.region3 = info.region3();
        this.lat = info.lat();
        this.lng = info.lng();
        this.phone = info.phone();
        this.placeUrl = info.placeUrl();
        this.category = category;
        this.rating = rating;
        this.oneLineReview = oneLineReview;
    }

    /** 사양서 2.2: 남이 공유한 장소는 ADMIN 이상만 고치거나 지운다 */
    public boolean isEditableBy(long userId, GroupRole role) {
        return sharedBy == userId || role.atLeast(GroupRole.ADMIN);
    }

    public void changeCategory(PlaceCategory category) {
        this.category = category;
    }

    public void changeRating(Integer rating) {
        this.rating = rating;
    }

    public void changeOneLineReview(String oneLineReview) {
        this.oneLineReview = oneLineReview;
    }

    /** 카카오 검색 결과에서 가져와 저장하는 값 */
    public record KakaoInfo(String kakaoPlaceId, String name, String categoryDetail, String address, String region1,
            String region2, String region3, double lat, double lng, String phone, String placeUrl) {
    }
}
