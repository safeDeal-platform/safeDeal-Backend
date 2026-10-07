package com.safedeal.global.security;

/**
 * 인증된 사용자를 나타내는 principal 계약 — 타입을 여기서 고정하지 않으면 담당자마다
 * SecurityContext를 다르게 캐스팅하게 되고, 나중에 JWT 필터가 들어올 때 그 코드가 다 깨진다.
 *
 * @param userId users.id (BIGINT PK) — 외부 노출용 public_id가 아니라 내부 PK다.
 * @param role   권한 이름(예: USER, ADMIN). "ROLE_" 접두어는 뺀 순수 이름.
 */
public record AuthenticatedUser(Long userId, String role) {
}
