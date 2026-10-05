package com.safedeal.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 메일 링크로 받은 인증 토큰 (AUTH-6). 쿼리 파라미터가 아니라 본문으로 받는다 — 쿼리에 실으면
 * 토큰이 각종 로그에 그대로 남아, 로그를 볼 수 있는 사람이 남의 인증 링크를 그대로 쓸 수 있다.
 */
public record EmailVerifyRequest(
        @NotBlank String token
) {
}
