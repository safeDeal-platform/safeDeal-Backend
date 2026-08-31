package com.safedeal.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 메일 링크로 받은 인증 토큰 (AUTH-6).
 *
 * 쿼리 파라미터가 아니라 본문으로 받는다 — 쿼리에 실으면 토큰이 액세스 로그·프록시 로그·
 * Referer 헤더에 그대로 남아, 로그를 볼 수 있는 사람이 남의 인증 링크를 그대로 쓸 수 있다.
 * 메일 본문의 링크는 프런트엔드 화면을 가리키고, 그 화면이 토큰을 이 API에 실어 보낸다.
 */
public record EmailVerifyRequest(
        @NotBlank String token
) {
}
