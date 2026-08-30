package com.safedeal.domain.auth.service;

import com.safedeal.global.security.JwtTokenProvider.IssuedToken;

/**
 * 로그인·재발급이 만들어내는 토큰 한 쌍.
 *
 * 응답 DTO가 아니라 서비스 계층 반환값이다 — refresh는 httpOnly 쿠키로만 나가야 하므로
 * 이 타입을 그대로 직렬화해서 응답 바디에 실으면 안 된다. 컨트롤러가 access는 바디로,
 * refresh는 Set-Cookie로 나눠 담는다.
 */
public record AuthTokens(IssuedToken access, IssuedToken refresh) {
}
