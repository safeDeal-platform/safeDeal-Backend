package com.safedeal.domain.auth.service;

import com.safedeal.domain.user.entity.User;
import com.safedeal.global.security.JwtTokenProvider.IssuedToken;

/**
 * 로그인·재발급이 만들어내는 결과 — 토큰 한 쌍 + 그 토큰의 주인.
 *
 * 응답 DTO가 아니라 서비스 계층 반환값이다 — refresh는 httpOnly 쿠키로만 나가야 하므로
 * 이 타입을 그대로 직렬화해서 응답 바디에 실으면 안 된다. 컨트롤러가 access는 바디로,
 * refresh는 Set-Cookie로 나눠 담는다.
 *
 * {@code user}를 함께 싣는 이유: 가입·로그인 응답이 닉네임·인증여부·신뢰도까지 돌려주므로
 * (API 명세서 AUTH-1·AUTH-2) 컨트롤러가 유저를 다시 조회하지 않게 한다. 재발급은 이 필드를
 * 쓰지 않지만, 세 경로가 같은 반환 타입을 쓰는 편이 분기보다 단순하다.
 */
public record AuthTokens(User user, IssuedToken access, IssuedToken refresh) {
}
