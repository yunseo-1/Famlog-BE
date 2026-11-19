package com.famlog.candidate.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.famlog.candidate.domain.AiTrigger;
import com.famlog.support.Family;
import com.famlog.support.GroupApi;
import com.famlog.support.IntegrationTest;

/**
 * AI 호출·비용 상한 (사양서 7.2): 그룹별 하루(KST) 100회, 월(UTC) 20달러, 80% 경고는 그달에 한 번.
 * 컨텍스트·DB 를 다른 테스트와 나눠 쓰므로 비용 확인은 먼 미래의 달(2030년)에 넣은 요청으로 한다.
 */
class AiCostGuardTest extends IntegrationTest {

    @Autowired
    AiCostGuard guard;

    @Autowired
    ExtractJobService jobService;

    @Autowired
    StringRedisTemplate redis;

    private Family family;
    private long roomId;

    @BeforeEach
    void setUp() throws Exception {
        family = Family.create(mockMvc, this::createUser);
        roomId = jdbcTemplate.queryForObject("SELECT id FROM chat_rooms WHERE group_id = ?", Long.class,
                family.groupId());
        redis.delete(redis.keys("ai:cost-warned:*2030-*"));
    }

    @Test
    void 그룹별_하루_100회째까지_만들고_그_뒤는_만들지_않는다() throws Exception {
        Instant now = Instant.now();
        insertJobs(family.groupId(), 99, now, 0, 0);
        assertThat(guard.allows(family.groupId(), now)).isTrue();

        insertJobs(family.groupId(), 1, now, 0, 0);

        assertThat(guard.allows(family.groupId(), now)).isFalse();
        Family other = Family.create(mockMvc, this::createUser);
        assertThat(guard.allows(other.groupId(), now)).isTrue();
    }

    @Test
    void 하루는_한국_시간_자정에_바뀐다() {
        Instant now = OffsetDateTime.parse("2030-03-10T09:00:00+09:00").toInstant();
        insertJobs(family.groupId(), 100, OffsetDateTime.parse("2030-03-09T23:59:00+09:00").toInstant(), 0, 0);

        assertThat(guard.allows(family.groupId(), now)).isTrue();
    }

    @Test
    void 월_비용은_토큰_종류별_단가로_계산하고_상한에_닿으면_만들지_않는다() {
        Instant jan = OffsetDateTime.parse("2030-01-10T00:00:00Z").toInstant();
        // 입력 1억 × 0.10 + 출력 2천만 × 0.50 = 20달러
        insertJobs(family.groupId(), 1, jan, 100_000_000, 20_000_000);

        assertThat(guard.monthlyCost(YearMonth.of(2030, 1))).isEqualByComparingTo("20");
        assertThat(guard.allows(family.groupId(), OffsetDateTime.parse("2030-01-20T00:00:00Z").toInstant()))
                .isFalse();
        assertThat(guard.monthlyCost(YearMonth.of(2031, 6))).isEqualByComparingTo(BigDecimal.ZERO); // 요청 없는 달
    }

    @Test
    void 캐시_토큰은_읽기_0_01_쓰기_0_125_단가다() {
        Instant apr = OffsetDateTime.parse("2030-04-10T00:00:00Z").toInstant();
        long jobId = insertJobs(family.groupId(), 1, apr, 0, 0);
        jdbcTemplate.update("UPDATE ai_jobs SET cache_read_tokens = 100000000, cache_write_tokens = 8000000 "
                + "WHERE id = ?", jobId);

        assertThat(guard.monthlyCost(YearMonth.of(2030, 4))).isEqualByComparingTo("2"); // 1 + 1
    }

    @Test
    void 상한의_80퍼센트를_넘으면_경고는_그달에_한_번만_남기고_계속_만든다() {
        Instant feb = OffsetDateTime.parse("2030-02-10T00:00:00Z").toInstant();
        insertJobs(family.groupId(), 1, feb, 0, 32_000_000); // 16달러

        Instant later = OffsetDateTime.parse("2030-02-20T00:00:00Z").toInstant();
        assertThat(guard.allows(family.groupId(), later)).isTrue();
        assertThat(guard.allows(family.groupId(), later)).isTrue();

        assertThat(redis.hasKey("ai:cost-warned:warn:2030-02")).isTrue();
        assertThat(redis.getExpire("ai:cost-warned:warn:2030-02")).isPositive();
    }

    @Test
    void 상한에_걸리면_요청을_만들지_않고_그_구간은_다시_분석하지_않는다() throws Exception {
        GroupApi.setAiAnalysis(mockMvc, family.groupId(), family.mom(), true);
        insertJobs(family.groupId(), 100, Instant.now(), 0, 0);
        long seq = jdbcTemplate.queryForObject("SELECT last_seq FROM chat_rooms WHERE id = ?", Long.class, roomId) + 1;
        jdbcTemplate.update("INSERT INTO messages (room_id, seq, sender_id, type, body) VALUES (?, ?, ?, 'TEXT', ?)",
                roomId, seq, family.mom(), "내일 11시에 병원");
        jdbcTemplate.update("UPDATE chat_rooms SET last_seq = ? WHERE id = ?", seq, roomId);

        assertThat(jobService.create(roomId, AiTrigger.RULE)).isEmpty();

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM ai_jobs WHERE group_id = ?", Integer.class,
                family.groupId())).isEqualTo(100);
        assertThat(jdbcTemplate.queryForObject("SELECT ai_analyzed_seq FROM chat_rooms WHERE id = ?", Long.class,
                roomId)).isEqualTo(seq);
    }

    /** @return 마지막으로 넣은 요청 id */
    private long insertJobs(long groupId, int count, Instant createdAt, int inputTokens, int outputTokens) {
        return jdbcTemplate.query("""
                INSERT INTO ai_jobs (group_id, room_id, from_seq, to_seq, trigger, message_count, member_refs,
                                     status, input_tokens, output_tokens, created_at)
                SELECT ?, ?, 0, 1, 'RULE', 1, '{}', 'DONE', ?, ?, ? FROM generate_series(1, ?)
                RETURNING id
                """, (rs, i) -> rs.getLong(1), groupId, roomId, inputTokens, outputTokens,
                Timestamp.from(createdAt), count).stream().reduce((a, b) -> b).orElseThrow();
    }
}
