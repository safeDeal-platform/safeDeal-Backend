package com.safedeal.domain.auth.dto;

import com.safedeal.global.security.JwtTokenProvider.IssuedToken;

/**
 * 토큰 재발급 응답 바디 (API 명세서 AUTH-3).
 *
 * access 토큰만 담는다 - refresh는 httpOnly 쿠키로만 나가야 하고(정책 '쿠키 전달'),
 * 바디에 함께 실으면 JS가 읽을 수 있게 되어 httpOnly를 둔 이유가 사라진다.
 *
 * 가입·로그인과 달리 유저 정보를 싣지 않는다. 재발급은 이미 로그인된 세션을 잇는 것이라
 * 프론트가 닉네임·신뢰도를 이미 들고 있고, 명세서도 accessToken 하나만 적어놨다.
 *
 * @param expiresIn 남은 유효 시간(초). 프론트가 사일런트 재발급 시점을 잡는 데 쓴다.
 */
public record TokenResponse(String accessToken, long expiresIn) {

    public static TokenResponse from(IssuedToken accessToken) {
        return new TokenResponse(accessToken.value(),
                AuthResponse.remainingSeconds(accessToken.expiresAt()));
    }
}
