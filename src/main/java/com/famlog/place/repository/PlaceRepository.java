package com.famlog.place.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.famlog.place.domain.Place;

public interface PlaceRepository extends JpaRepository<Place, Long>, JpaSpecificationExecutor<Place> {

    boolean existsByGroupIdAndKakaoPlaceId(long groupId, String kakaoPlaceId);

    List<Place> findByGroupIdAndKakaoPlaceIdIn(long groupId, Collection<String> kakaoPlaceIds);

    /**
     * 기준점에서 radiusM 안의 장소, 가까운 순 (PLC-04). 기준 좌표는 이 쿼리에만 쓰고 저장하지 않는다.
     * geom 은 lat·lng 에서 DB 가 만든 geography 라 거리는 미터다.
     */
    @Query(value = """
            select p.id as id,
                   ST_Distance(p.geom, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) as distance
            from places p
            where p.group_id = :groupId
              and ST_DWithin(p.geom, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :radiusM)
            order by distance, p.id
            limit :limit
            """, nativeQuery = true)
    List<NearbyRow> findNearby(@Param("groupId") long groupId, @Param("lat") double lat, @Param("lng") double lng,
            @Param("radiusM") double radiusM, @Param("limit") int limit);

    interface NearbyRow {
        Long getId();

        Double getDistance();
    }
}
