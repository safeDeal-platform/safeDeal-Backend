package com.safedeal.global.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 로그아웃 시 무효화하는 access 토큰의 jti 목록(정책 '토큰 무효화'). 조회하는
 * 쪽이 {@link JwtAuthenticationFilter}(global)라서 domain/auth가 아니라 global에 둔다.
 *
 * 조회는 fail-open이다(정책 개정 2026-08-30) — Redis가 죽었다고 차단하면 정상 사용자
 * 전원이 401로 강제 로그아웃되고, 노출은 '이미 탈취된 토큰'에 장애 시간만큼만 한정된다.
 *
 * 등록(쓰기)이 실패해도 로그아웃 자체는 성공시킨다 — 쓰기 실패에 로그아웃까지 막으면
 * 보안은 못 얻으면서 쿠키만 남아 공용 PC에서 다음 사람이 남의 계정에 들어갈 수 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenBlacklist {

    private static final String KEY_PREFIX = "auth:blacklist:v1:";
    private static final String VALUE = "1";

    private final StringRedisTemplate redisTemplate;

    /** 남은 수명만큼만 보관한다 — 어차피 만료된 토큰은 서명 검증에서 걸리므로 더 둘 이유가 없다. */
    public void add(String jti, Instant expiresAt) {
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        if (ttl.isNegative() || ttl.isZero()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + jti, VALUE, ttl);
        } catch (RuntimeException e) {
            log.warn("블랙리스트 등록 실패 - 해당 access는 만료까지 유효하게 남는다. reason={}",
                    e.getClass().getSimpleName());
        }
    }

    public boolean contains(String jti) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + jti));
        } catch (RuntimeException e) {
            log.warn("블랙리스트 조회 실패 - fail-open으로 통과시킨다. reason={}", e.getClass().getSimpleName());
            return false;
        }
    }
}
