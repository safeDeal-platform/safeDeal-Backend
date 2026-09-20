package com.safedeal.domain.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * 비밀번호 재설정 링크 요청 (AUTH-7).
 *
 * 응답은 계정 존재 여부와 무관하게 항상 같다 — 다르게 주면 이 API가 곧 "가입 여부 조회기"가 된다.
 */
public record PasswordResetRequest(
        @NotBlank @Email @jakarta.validation.constraints.Size(max = 255) String email
) {
}
