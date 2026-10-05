package com.safedeal.domain.auth.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;

/**
 * 메일을 유발하는 요청의 호출 횟수 제한 (AUTH-6 재발송 · AUTH-7 재설정 요청). 로그인 없이도
 * 부를 수 있고 호출 한 번이 메일 한 통이라, 제한이 없으면 발송 쿼터를 태워 다른 모든
 * 사용자의 메일까지 멈출 수 있다.
 *
 * 주소와 IP를 함께 본다 — 주소만 보면 주소를 바꿔가며 쿼터를 태우고, IP만 보면 한 사람 메일함을
 * 여러 IP로 폭격하는 걸 못 막는다. 주소는 해시로 키를 만든다(가입 이메일이 드러나면 안 된다).
 *
 * Redis 장애 시엔 통과시킨다(fail-open) — 막으면 비밀번호를 잃은 사용자가 복구 수단을 통째로 잃는다.
 * 한도 값 자체는 이 클래스가 갖고, {@code rate_limit.lua}는 횟수만 센다.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class MailSendRateLimiter {

    private static final String KEY_PREFIX = "auth:mail-rate:v1:";

    private static final Duration WINDOW = Duration.ofHours(1);

    /** 한 주소(또는 한 계정)에 시간당 허용하는 메일 수. 오타로 재요청하는 경우까지 감안한 값. */
    private static final int PER_ADDRESS_LIMIT = 3;

    /** 한 IP에서 시간당 허용하는 메일 수. 회사·학교 NAT를 감안해 주소 기준보다 넉넉하게 둔다. */
    private static final int PER_IP_LIMIT = 20;

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> rateLimitScript;

    /**
     * 회원가입 (AUTH-1). 가입 한 번이 인증 메일 한 통이라 이 경로도 세야 한다. IP만 본다 —
     * 주소는 공격자가 매번 바꿀 수 있고, 같은 주소 반복은 어차피 email UNIQUE가 막아준다.
     */
    public boolean allowSignup(String ip) {
        return allow("signup:ip:" + ip, PER_IP_LIMIT);
    }

    /** 재설정 요청 (AUTH-7). 계정 존재 여부를 보기 <b>전에</b> 호출해야 응답이 갈라지지 않는다. */
    public boolean allowPasswordResetRequest(String emailHash, String ip) {
        return allow("reset:addr:" + emailHash, PER_ADDRESS_LIMIT)
                && allow("reset:ip:" + ip, PER_IP_LIMIT);
    }

    /** 인증 메일 재발송 (AUTH-6). 로그인이 필요한 API라 계정 단위로만 센다. */
    public boolean allowVerificationResend(Long userId) {
        return allow("verify:user:" + userId, PER_ADDRESS_LIMIT);
    }

    private boolean allow(String suffix, int limit) {
        try {
            Long count = redisTemplate.execute(
                    rateLimitScript,
                    List.of(KEY_PREFIX + suffix),
                    String.valueOf(WINDOW.toSeconds()));
            return count == null || count <= limit;
        } catch (RuntimeException e) {
            log.warn("메일 발송 제한 조회 실패 - fail-open으로 통과시킨다. reason={}", e.getClass().getSimpleName());
            return true;
        }
    }
}
