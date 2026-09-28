package com.safedeal.domain.auth.dto;

import com.safedeal.domain.auth.service.AuthTokens;
import com.safedeal.domain.trust.TrustScore;
import com.safedeal.domain.user.entity.User;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 회원가입·로그인 응답 바디 (API 명세서 AUTH-1 · AUTH-2). 가입과 로그인 응답을 하나로
 * 합쳤다 — 가입 즉시 로그인 상태가 되므로 두 응답이 가리키는 상태가 같다.
 *
 * refresh 토큰은 담지 않는다 — httpOnly 쿠키로만 나가야 하는데 바디에 실으면 자바스크립트가
 * 읽을 수 있게 되어버린다(정책 '쿠키 전달').
 *
 * @param trustScore 화면에 보여줄 값(내부 0~1000점을 10으로 나눈 소수 1자리, 정책 TRS-1).
 *                   변환은 TrustScore.display 한 곳에서만 하므로 척도가 바뀌어도 거기만 고치면 된다.
 * @param expiresIn  access 토큰이 몇 초 후 만료되는지. 프론트가 토큰 내용을 직접 열어보지
 *                   않고도 재발급 시점을 알 수 있게 함께 내려준다.
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
