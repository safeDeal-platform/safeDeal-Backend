package com.safedeal.global.security;

/**
 * 인증된 사용자를 나타내는 principal 계약.
 *
 * 컨트롤러에서 현재 사용자는 반드시 {@code @AuthenticationPrincipal AuthenticatedUser}로 꺼낸다.
 * principal 타입을 여기서 하나로 고정하지 않으면 담당자마다 SecurityContext에서 문자열을
 * 캐스팅하거나 Authentication.getName()을 파싱하는 식으로 제각각 꺼내게 되고, 나중에 JWT
 * 필터가 들어올 때 그 코드 전부가 깨진다.
 *
 * 이 계약을 지켜야 하는 곳:
 * - {@link DevAuthenticationFilter} — 개발용 우회 인증 (이미 적용)
 * - 향후 JWT 인증 필터 — 토큰 클레임을 파싱해 반드시 같은 타입으로 principal을 만든다
 *
 * @param userId users.id (BIGINT PK). 외부 노출용 public_id가 아니라 내부 PK다 —
 *               도메인 로직·조인이 전부 내부 PK 기준이기 때문.
 * @param role   권한 이름 (예: USER, ADMIN). "ROLE_" 접두어를 뺀 순수 이름.
 */
public record AuthenticatedUser(Long userId, String role) {
}
