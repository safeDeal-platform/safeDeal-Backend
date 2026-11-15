package com.safedeal.domain.report.repository;

import com.safedeal.testsupport.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 하루 한도 카운터를 실제 Redis와 {@code rate_limit.lua}로 돌린다 — TTL 인자가 스크립트의 허용 범위
 * (1~86,400초) 안에 들어가는지, 한국 날짜가 바뀌면 새 키로 세는지 본다.
 */
class ReportDailyLimiterIntegrationTest extends IntegrationTestSupport {

    private static final Long REPORTER = 987_654_321L;
    /** 한국 시각 2026-11-15 10:00. */
    private static final Instant KST_MORNING = Instant.parse("2026-11-15T01:00:00Z");
    /** 한국 시각 2026-11-16 00:00 — 한국 날짜로 다음 날. */
    private static final Instant KST_NEXT_MIDNIGHT = Instant.parse("2026-11-15T15:00:00Z");

    @Autowired ReportDailyLimiter dailyLimiter;
    @Autowired StringRedisTemplate redisTemplate;

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(redisTemplate.keys("rate:v1:" + REPORTER + ":report:*"));
    }

    @Test
    @DisplayName("RPT-C24 RPT-C25 하루 10번째까지 통과하고 11번째에서 막힌다")
    void allowsTenThenRejects() {
        for (int i = 1; i <= 10; i++) {
            assertThat(dailyLimiter.tryAcquire(REPORTER, KST_MORNING)).as(i + "번째").isTrue();
        }

        assertThat(dailyLimiter.tryAcquire(REPORTER, KST_MORNING)).as("11번째").isFalse();
    }

    @Test
    @DisplayName("RPT-C24 카운터 키는 한국 날짜이고 TTL은 한국 자정까지(14시간 이하)로 걸린다")
    void keyHasKoreanDateAndTtlUntilMidnight() {
        dailyLimiter.tryAcquire(REPORTER, KST_MORNING);

        Long ttl = redisTemplate.getExpire("rate:v1:" + REPORTER + ":report:20261115");
        assertThat(ttl).isBetween(50_000L, 50_400L);
    }

    @Test
    @DisplayName("RPT-C26 한국 자정이 지나면 새 날짜 키로 다시 센다")
    void resetsOnNextKoreanDay() {
        for (int i = 0; i < 11; i++) {
            dailyLimiter.tryAcquire(REPORTER, KST_MORNING);
        }

        assertThat(dailyLimiter.tryAcquire(REPORTER, KST_NEXT_MIDNIGHT)).isTrue();
    }
}
