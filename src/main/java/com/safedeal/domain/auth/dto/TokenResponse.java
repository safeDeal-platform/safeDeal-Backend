package com.safedeal.domain.auth.dto;

import com.safedeal.global.security.JwtTokenProvider.IssuedToken;

import java.time.Instant;

/**
 * 로그인·회원가입 응답 바디.
 *
 * access 토큰만 담는다 - refresh는 httpOnly 쿠키로만 나가야 하고(정책 '쿠키 전달'),
 * 바디에 함께 실으면 JS가 읽을 수 있게 되어 httpOnly를 둔 이유가 사라진다.
 *
 * access를 헤더가 아니라 바디로 주는 것도 정책 확정 사항이다(2026-07-26).
 * 프론트는 이 값을 메모리에만 보관한다(localStorage 금지).
 *
 * @param expiresIn 남은 유효 시간(초). 프론트가 사일런트 재발급 시점을 잡는 데 쓴다.
 */
public record TokenResponse(String accessToken, long expiresIn) {

    public static TokenResponse from(IssuedToken accessToken) {
        long expiresIn = Math.max(0, accessToken.expiresAt().getEpochSecond() - Instant.now().getEpochSecond());
        return new TokenResponse(accessToken.value(), expiresIn);
    }
}
