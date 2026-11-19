package com.safedeal.domain.auth.repository;

import com.safedeal.global.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

/**
 * 카카오 로그인(AUTH-5)의 짧은 수명 1회용 값 두 가지.
 *
 * <pre>
 * auth:oauth-state:v1:{hash(state)}     STRING  "1"                        TTL 10분
 * auth:oauth-signup:v1:{hash(ticket)}   STRING  providerUserId|email       TTL 10분
 * </pre>
 *
 * <ul>
 *   <li><b>state</b> — 카카오로 보낼 때 실어 보내고 콜백에서 대조한다(로그인 CSRF 방지).
 *       같은 값을 브라우저 쿠키에도 심어, "이 브라우저가 시작한 로그인"인지까지 본다(컨트롤러 참고).</li>
 *   <li><b>가입 티켓</b> — 처음 온 카카오 사용자가 닉네임을 입력하는 동안 카카오 정보를 맡아둔다.
 *       서버 세션이 없어서 여기 둔다.</li>
 * </ul>
 *
 * 키는 원문이 아니라 해시로 만든다 — Redis 키 목록만 봐도 쓸 수 있는 값이 드러나면 안 된다.
 * 소진은 DEL 반환값으로 판정한다(RefreshTokenStore.consume과 같은 방식) — 동시에 두 번 와도 하나만 통과한다.
 *
 * 실패는 삼키지 않는다(fail-closed) — 둘 다 "있어야 통과"하는 값이라, 조회 실패를 통과로 바꾸면 방어가 사라진다.
 */
@Repository
@RequiredArgsConstructor
public class OauthTicketStore {

    private static final String STATE_PREFIX = "auth:oauth-state:v1:";
    private static final String SIGNUP_PREFIX = "auth:oauth-signup:v1:";
    private static final String DELIMITER = "|";
    public static final Duration TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redisTemplate;

    /** 카카오 정보를 맡아둔 가입 티켓의 내용. */
    public record SignupTicket(String providerUserId, String email) {
    }

    public void saveState(String state) {
        redisTemplate.opsForValue().set(STATE_PREFIX + JwtTokenProvider.hash(state), "1", TTL);
    }

    /** 실제로 지운 요청만 true — 재사용·만료·위조는 모두 false. */
    public boolean consumeState(String state) {
        return Boolean.TRUE.equals(redisTemplate.delete(STATE_PREFIX + JwtTokenProvider.hash(state)));
    }

    public void saveSignupTicket(String ticket, String providerUserId, String email) {
        redisTemplate.opsForValue().set(
                SIGNUP_PREFIX + JwtTokenProvider.hash(ticket), providerUserId + DELIMITER + email, TTL);
    }

    /**
     * 소진하지 않고 들여다본다 — 닉네임 중복처럼 흔한 실패에서는 티켓을 살려 두어 카카오 로그인부터
     * 다시 하지 않게 한다. 실제 소진은 계정을 만든 뒤 {@link #consumeSignupTicket}으로 한다.
     */
    public Optional<SignupTicket> findSignupTicket(String ticket) {
        String raw = redisTemplate.opsForValue().get(SIGNUP_PREFIX + JwtTokenProvider.hash(ticket));
        if (raw == null) {
            return Optional.empty();
        }
        // 회원번호(숫자)가 앞이라 첫 구분자에서만 자른다 — 이메일에 '|'가 들어가도 깨지지 않는다.
        int cut = raw.indexOf(DELIMITER);
        return Optional.of(new SignupTicket(raw.substring(0, cut), raw.substring(cut + 1)));
    }

    public boolean consumeSignupTicket(String ticket) {
        return Boolean.TRUE.equals(redisTemplate.delete(SIGNUP_PREFIX + JwtTokenProvider.hash(ticket)));
    }
}
