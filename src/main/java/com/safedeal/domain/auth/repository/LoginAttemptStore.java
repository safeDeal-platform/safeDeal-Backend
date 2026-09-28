package com.safedeal.domain.auth.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;

/**
 * 로그인 실패 횟수 카운터 (정책 AUTH-8 브루트포스 방어). 계정과 IP를 함께 본다 — 계정만 보면
 * 공격자가 남의 계정을 일부러 틀려 잠글 수 있고, IP만 보면 같은 공유망 사용자들이 서로를 잠근다.
 *
 * 실패가 쌓일수록 잠금 시간을 늘린다(지수 백오프). Redis가 죽으면 통과시킨다(fail-open) —
 * 막으면 정상 사용자도 못 들어오는데, 통과시켜도 공격자는 여전히 비밀번호를 맞혀야 한다.
 *
 * 계정 식별자는 원문이 아니라 해시로 키를 만든다 — Redis 키 목록만 봐도 가입 이메일이 드러나면 안 된다.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class LoginAttemptStore {

    private static final String KEY_PREFIX = "auth:login-fail:v1:";

    /** 이 횟수부터 잠근다. */
    private static final int LOCK_THRESHOLD = 5;

    private static final Duration BASE_LOCK = Duration.ofMinutes(1);
    private static final Duration MAX_LOCK = Duration.ofMinutes(15);

    private final StringRedisTemplate redisTemplate;

    public boolean isLocked(String accountKey, String ip) {
        try {
            String raw = redisTemplate.opsForValue().get(key(accountKey, ip));
            return raw != null && Integer.parseInt(raw) >= LOCK_THRESHOLD;
        } catch (RuntimeException e) {
            log.warn("로그인 잠금 조회 실패 - fail-open으로 통과시킨다. reason={}", e.getClass().getSimpleName());
            return false;
        }
    }

    /** 실패를 1 올리고 잠금 시간을 다시 건다. 반환값은 누적 실패 횟수. */
    public long recordFailure(String accountKey, String ip) {
        try {
            String key = key(accountKey, ip);
            Long count = redisTemplate.opsForValue().increment(key);
            if (count == null) {
                return 0;
            }
            // INCR과 EXPIRE 사이에 다른 요청이 끼어들면 TTL이 한 번 덜 걸릴 수 있지만 다음 실패에서 곧 복구된다.
            // rate_limit.lua를 안 쓰는 이유: 그 스크립트는 "최초 1회만 EXPIRE"라 매번 늘려야 하는 지수 백오프와 안 맞는다.
            redisTemplate.expire(key, lockDuration(count));
            return count;
        } catch (RuntimeException e) {
            log.warn("로그인 실패 기록 실패 - reason={}", e.getClass().getSimpleName());
            return 0;
        }
    }

    public void clear(String accountKey, String ip) {
        try {
            redisTemplate.delete(key(accountKey, ip));
        } catch (RuntimeException e) {
            log.warn("로그인 실패 카운터 정리 실패 - reason={}", e.getClass().getSimpleName());
        }
    }

    private Duration lockDuration(long failureCount) {
        if (failureCount < LOCK_THRESHOLD) {
            // 아직 잠금 전이라도 카운터가 영원히 남으면 안 되므로 기본 창을 준다.
            return BASE_LOCK;
        }
        long steps = failureCount - LOCK_THRESHOLD;
        // 지수가 커지면 오버플로우가 나므로 상한 안에서만 배수를 계산한다.
        long minutes = steps >= 4 ? MAX_LOCK.toMinutes() : BASE_LOCK.toMinutes() << steps;
        return Duration.ofMinutes(Math.min(minutes, MAX_LOCK.toMinutes()));
    }

    private String key(String accountKey, String ip) {
        return KEY_PREFIX + accountKey + ":" + ip;
    }
}
