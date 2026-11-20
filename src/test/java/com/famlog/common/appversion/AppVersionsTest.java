package com.famlog.common.appversion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AppVersionsTest {

    @Test
    void 버전은_숫자로_비교하고_꼬리는_무시한다() {
        assertThat(AppVersions.compare("1.2.10", "1.2.9")).isPositive();
        assertThat(AppVersions.compare("1.2", "1.2.0")).isZero();
        assertThat(AppVersions.compare("1.2.0-beta", "1.2.0")).isZero();
        assertThat(AppVersions.compare("0.9.9", "1.0.0")).isNegative();
        assertThat(AppVersions.isVersion("1.0.0")).isTrue();
        assertThat(AppVersions.isVersion("1.x")).isFalse();
        assertThat(AppVersions.isVersion(null)).isFalse();
    }
}
