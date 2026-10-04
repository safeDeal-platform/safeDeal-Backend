package com.safedeal.domain.auth.service;

import com.safedeal.domain.user.entity.User;
import com.safedeal.global.security.JwtTokenProvider.IssuedToken;

/**
 * 로그인·재발급 결과 — 토큰 한 쌍과 그 주인. 응답 DTO가 아니다 — 이대로 직렬화해 바디에
 * 실으면 refresh가 노출되므로, 컨트롤러가 access는 바디로 refresh는 쿠키로 나눠 담는다.
 *
 * user를 함께 싣는 이유: 가입·로그인 응답에 필요해서다(재발급은 안 쓰지만 반환 타입을 공유하는 편이 단순하다).
 */
public record AuthTokens(User user, IssuedToken access, IssuedToken refresh) {
}
