package com.safedeal.domain.auth.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;

/**
 * 메일을 유발하는 요청의 호출 횟수 제한 (AUTH-6 재발송 · AUTH-7 재설정 요청).
 *
 * 비밀번호 재설정 요청은 <b>로그인 없이</b> 호출할 수 있고 호출 한 번이 곧 메일 한 통이다.
 * 제한이 없으면 남의 주소를 아는 사람이 그 사람 메일함을 폭격할 수 있고, 더 나쁘게는 공급자
 * 발송 쿼터를 태워 <b>다른 모든 사용자의 가입·재설정 메일까지 함께 멈춘다</b>. 로그인은
 * {@link LoginAttemptStore}가 막고 있는데 이 경로만 열려 있었다.
 *
 * 주소 기준과 IP 기준을 함께 본다. 주소만 보면 공격자가 주소를 바꿔가며 쿼터를 태울 수 있고,
 * IP만 보면 한 사람의 메일함을 여러 IP에서 폭격하는 것을 못 막는다.
 *
 * 주소는 원문이 아니라 해시로 키를 만든다 — Redis 키 목록만 봐도 가입자 이메일이 드러나면
 * 안 된다({@link LoginAttemptStore}와 같은 방식).
 *
 * <b>Redis 장애 시에는 통과시킨다(fail-open).</b> 팀에서 정한 인증 계열 공통 방침이고, 여기서
 * 막으면 Redis가 죽은 동안 비밀번호를 잃어버린 사용자가 복구 수단을 통째로 잃는다. 대신
 * 그동안 쿼터가 노출되는 것은 감수한다.
 *
 * 한도 자체는 이 클래스가 갖는다 — {@code rate_limit.lua}는 카운트만 돌려주고 판단하지
 * 않는다(스크립트 주석 참고).
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
     * 회원가입 (AUTH-1). 가입 한 번이 곧 인증 메일 한 통이라 이 경로도 세야 한다.
     *
     * 주소 기준은 의미가 없다 — 공격자는 매번 다른 주소를 쓰면 그만이고, 같은 주소의 반복은
     * 어차피 email UNIQUE가 막는다. 남는 축은 IP뿐이다.
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
