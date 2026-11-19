package com.famlog.place.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.famlog.place.domain.PlaceMark;

public interface PlaceMarkRepository extends JpaRepository<PlaceMark, PlaceMark.Key> {

    List<PlaceMark> findByPlaceIdIn(Collection<Long> placeIds);
}
