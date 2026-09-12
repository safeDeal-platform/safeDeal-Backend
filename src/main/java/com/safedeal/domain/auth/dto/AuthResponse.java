package com.safedeal.domain.auth.dto;

import com.safedeal.domain.auth.service.AuthTokens;
import com.safedeal.domain.trust.TrustScore;
import com.safedeal.domain.user.entity.User;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 회원가입·로그인 응답 바디 (API 명세서 AUTH-1 · AUTH-2).
 *
 * refresh는 담지 않는다 — httpOnly 쿠키로만 나가야 하고(정책 '쿠키 전달'), 바디에 함께 실으면
 * JS가 읽을 수 있게 되어 httpOnly를 둔 이유가 사라진다.
 *
 * access를 헤더가 아니라 바디로 주는 것도 정책 확정 사항이다(2026-07-26).
 * 프론트는 이 값을 메모리에만 보관한다(localStorage 금지).
 *
 * <b>가입과 로그인이 같은 모양인 이유</b>: 정책상 가입 즉시 로그인 상태로 진입하므로 두 응답이
 * 가리키는 상태가 같다. API 명세서는 가입에만 emailVerified·trustScore를, 로그인에만 role을
 * 적어놨는데 그건 작성 시점의 누락으로 보고 합쳤다(2026-09-06, 프론트 착수 전이라 비용 0).
 *
 * @param trustScore 표시값(내부 0~1000을 10으로 나눈 소수 1자리, 정책 TRS-1).
 *                   변환은 신뢰도 도메인의 TrustScore.display가 소유한다 — 척도가 바뀌면
 *                   그쪽 한 곳만 고치면 된다.
 * @param expiresIn  access의 남은 유효 시간(초). JWT의 exp 클레임에 같은 정보가 있지만,
 *                   프론트가 토큰을 디코드하지 않고도 재발급 시점을 잡게 하려고 함께 준다.
 */
public record AuthResponse(String publicId,
                           String nickname,
                           String role,
                           boolean emailVerified,
                           BigDecimal trustScore,
                           String accessToken,
                           long expiresIn) {

    public static AuthResponse from(AuthTokens tokens) {
        User user = tokens.user();
        return new AuthResponse(
                user.getPublicId(),
                user.getNickname(),
                user.getRole().name(),
                user.isEmailVerified(),
                TrustScore.display(user.getTrustScore()),
                tokens.access().value(),
                remainingSeconds(tokens.access().expiresAt()));
    }

    static long remainingSeconds(Instant expiresAt) {
        return Math.max(0, expiresAt.getEpochSecond() - Instant.now().getEpochSecond());
    }
}
