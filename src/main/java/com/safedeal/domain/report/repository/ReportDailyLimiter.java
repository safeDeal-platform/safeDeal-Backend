package com.safedeal.domain.report.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 신고 하루 한도(RPT003). 신고자 1명당 한국 날짜 기준 하루 10건이다. 키는 rate limit 규칙
 * {@code rate:v1:{reporterId}:report:{yyyyMMdd}}를 따르고, TTL은 그날 한국 자정까지다.
 *
 * <p>호출 자체가 1건 차감이다 — 그래서 서비스는 모든 검증·중복 판정을 통과한 뒤, 저장 직전에만 부른다(Q2).
 * 이후 DB 실패분은 돌려주지 않는다.
 *
 * <p>Redis 장애 시엔 통과시킨다(fail-open) — 한도는 남용 방지 장치일 뿐이라, 장애 때문에 정상 신고까지
 * 막을 이유가 없다. 카운트는 {@code rate_limit.lua}가 세고, 한도 값은 이 클래스가 갖는다.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ReportDailyLimiter {

    private static final String KEY_PREFIX = "rate:v1:";
    private static final String ROUTE = ":report:";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private static final int DAILY_LIMIT = 10;

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> rateLimitScript;

    /** 1건을 센다. 오늘(한국 날짜) 10건째까지 true, 11건째부터 false. */
    public boolean tryAcquire(Long reporterId, Instant now) {
        LocalDate today = now.atZone(KST).toLocalDate();
        try {
            Long count = redisTemplate.execute(
                    rateLimitScript,
                    List.of(KEY_PREFIX + reporterId + ROUTE + today.format(DAY)),
                    String.valueOf(secondsUntilMidnight(today, now)));
            return count == null || count <= DAILY_LIMIT;
        } catch (RuntimeException e) {
            log.warn("신고 하루 한도 조회 실패 - fail-open으로 통과시킨다. reason={}", e.getClass().getSimpleName());
            return true;
        }
    }

    /**
     * 한국 자정까지 남은 초. 올림한다 — 내림하면 자정 직전 1초 미만 구간에서 0이 되어 스크립트가 TTL을 거부하고
     * (허용 범위 1~86,400), 그 시도는 세지 않은 채 fail-open으로 통과한다.
     */
    private static long secondsUntilMidnight(LocalDate today, Instant now) {
        Duration left = Duration.between(now, today.plusDays(1).atStartOfDay(KST).toInstant());
        return left.getNano() == 0 ? left.getSeconds() : left.getSeconds() + 1;
    }
}
