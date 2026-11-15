package com.safedeal.domain.report.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 신고 하루 한도(RPT003, 10건) 카운터. 날짜는 한국 날짜로 끊고, TTL은 한국 자정까지다.
 * 키 형식은 {@code RedisConfig}의 rate limit 규칙({@code rate:v1:{actorId}:{route}:{window}})을 따른다.
 * Redis 장애를 일으켜야 해서 mock으로 검증한다(실제 Redis 동작은 {@code ReportDailyLimiterIntegrationTest}).
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class ReportDailyLimiterTest {

    private static final Long REPORTER = 20L;
    /** 한국 시각 2026-11-15 10:00. 자정까지 14시간. */
    private static final Instant KST_MORNING = Instant.parse("2026-11-15T01:00:00Z");

    @Mock StringRedisTemplate redisTemplate;
    @Mock RedisScript<Long> rateLimitScript;

    private ReportDailyLimiter limiter() {
        return new ReportDailyLimiter(redisTemplate, rateLimitScript);
    }

    private void givenCount(String key, String ttlSeconds, long count) {
        when(redisTemplate.execute(eq(rateLimitScript), eq(List.of(key)), eq(ttlSeconds))).thenReturn(count);
    }

    @Test
    @DisplayName("RPT-C24 오늘 10번째 신고는 통과한다 - 키에 한국 날짜, TTL은 한국 자정까지")
    void tenthReportIsAllowed() {
        givenCount("rate:v1:20:report:20261115", "50400", 10L);

        assertThat(limiter().tryAcquire(REPORTER, KST_MORNING)).isTrue();
    }

    @Test
    @DisplayName("RPT-C25 오늘 11번째 신고는 막힌다")
    void eleventhReportIsRejected() {
        givenCount("rate:v1:20:report:20261115", "50400", 11L);

        assertThat(limiter().tryAcquire(REPORTER, KST_MORNING)).isFalse();
        // "false"만 보면 카운터를 아예 안 세는 구현도 통과한다 — 실제로 센 결과로 막았는지 확인한다.
        verify(redisTemplate).execute(eq(rateLimitScript), eq(List.of("rate:v1:20:report:20261115")), eq("50400"));
    }

    @Test
    @DisplayName("RPT-C26 한국 자정 직전은 그날 키(TTL 1초), 자정이 되면 다음 날 키(TTL 24시간)로 센다")
    void resetsAtKoreanMidnight() {
        givenCount("rate:v1:20:report:20261115", "1", 11L);
        givenCount("rate:v1:20:report:20261116", "86400", 1L);

        assertThat(limiter().tryAcquire(REPORTER, Instant.parse("2026-11-15T14:59:59Z"))).isFalse();
        assertThat(limiter().tryAcquire(REPORTER, Instant.parse("2026-11-15T15:00:00Z"))).isTrue();
    }

    @Test
    @DisplayName("RPT-C26 UTC로는 아직 15일이어도 한국 날짜가 16일이면 16일 키로 센다")
    void usesKoreanDateNotUtcDate() {
        givenCount("rate:v1:20:report:20261116", "82800", 1L);

        assertThat(limiter().tryAcquire(REPORTER, Instant.parse("2026-11-15T16:00:00Z"))).isTrue();
    }

    @Test
    @DisplayName("RPT-C41 Redis 장애면 한도 없이 통과시키고(fail-open) warn 로그를 남긴다")
    void redisFailureFailsOpenWithWarnLog(CapturedOutput output) {
        when(redisTemplate.execute(eq(rateLimitScript), anyList(), any()))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        assertThat(limiter().tryAcquire(REPORTER, KST_MORNING)).isTrue();
        assertThat(output.getOut().lines())
                .anyMatch(line -> line.contains("WARN") && line.contains("ReportDailyLimiter"));
    }
}
