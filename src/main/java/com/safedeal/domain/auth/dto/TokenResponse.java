package com.safedeal.domain.auth.dto;

import com.safedeal.global.security.JwtTokenProvider.IssuedToken;

/**
 * 토큰 재발급 응답 바디 (API 명세서 AUTH-3). access 토큰만 담는다 — refresh는 httpOnly
 * 쿠키로만 나가야 해서(정책 '쿠키 전달'), 바디에 실으면 자바스크립트가 읽을 수 있게 되어버린다.
 *
 * @param expiresIn access 토큰이 몇 초 후 만료되는지. 프론트가 재발급 시점을 잡는 데 쓴다.
 */
public record TokenResponse(String accessToken, long expiresIn) {

    public static TokenResponse from(IssuedToken accessToken) {
        return new TokenResponse(accessToken.value(),
                AuthResponse.remainingSeconds(accessToken.expiresAt()));
    }
}
