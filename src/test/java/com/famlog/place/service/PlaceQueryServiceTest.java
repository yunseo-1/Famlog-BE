package com.famlog.place.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorCode;
import com.famlog.common.flag.FeatureFlags;
import com.famlog.common.source.ItemSources;
import com.famlog.group.service.GroupAccessChecker;
import com.famlog.place.repository.LocationAccessLogRepository;
import com.famlog.place.repository.PlaceMarkRepository;
import com.famlog.place.repository.PlaceRepository;
import com.famlog.user.repository.ConsentRepository;

/** 내 주변 기능 플래그 (사양서 8.6 출시 전 할 일): 끄면 404 이고 위치를 쓰지도, 기록하지도 않는다. */
class PlaceQueryServiceTest {

    @Test
    void 내_주변이_꺼져_있으면_404이고_아무것도_하지_않는다() {
        PlaceRepository places = mock(PlaceRepository.class);
        LocationAccessLogRepository logs = mock(LocationAccessLogRepository.class);
        GroupAccessChecker checker = mock(GroupAccessChecker.class);
        PlaceProperties off = new PlaceProperties("https://dapi.kakao.com", "", Duration.ofHours(1),
                Duration.ofMinutes(10), 30, Duration.ofMinutes(1), false, 50, 100, Map.of());
        FeatureFlags flags = mock(FeatureFlags.class);
        when(flags.enabled(any(), anyBoolean())).thenAnswer(call -> call.getArgument(1));
        PlaceQueryService service = new PlaceQueryService(places, mock(PlaceMarkRepository.class), logs, checker,
                mock(ItemSources.class), mock(ConsentRepository.class), off, flags, Clock.systemUTC());

        assertThatThrownBy(() -> service.nearby(1, 2, 37.5, 127.0, 3))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        verifyNoInteractions(places, logs, checker);
    }
}
