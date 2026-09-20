package com.safedeal.global.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 로그아웃·비밀번호 변경으로 무효화된 access 토큰의 jti 목록 (정책 '토큰 무효화').
 *
 * global에 두는 이유: 조회하는 쪽이 {@link JwtAuthenticationFilter}(global)라서
 * domain/auth에 두면 global 에서 domain 으로 향하는 역방향 의존이 생긴다.
 *
 * <b>조회는 fail-open이다</b> (정책 개정 2026-08-30). Redis가 죽었을 때 차단해서 얻는 보안은
 * 장애 지속 시간 동안뿐인데(복구되면 다시 조회돼 차단되고, 데이터가 유실됐다면 fail-closed도
 * 못 막는다), 그 대가로 같은 시간 동안 정상 사용자 전원이 401 이 되고 silent refresh 도 401 이라
 * 재로그인조차 실패해 강제 로그아웃된다. fail-open의 노출은 '이미 탈취된 토큰'에 한정되고
 * 창은 장애 시간과 같다.
 *
 * <b>등록(쓰기)은 반대다</b> — 실패해도 로그아웃 자체는 성공시키고 경고만 남긴다.
 * 쓰기가 이미 실패한 마당에 로그아웃을 거절하면 보안은 하나도 못 얻으면서 사용자만 막고,
 * 쿠키가 남아 공용 PC에서 다음 사용자가 남의 계정에 로그인되는 더 나쁜 결과가 된다.
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
