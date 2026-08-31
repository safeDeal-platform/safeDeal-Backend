package com.safedeal.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 재설정 링크로 새 비밀번호를 설정한다 (AUTH-7).
 *
 * 길이 제약은 회원가입과 같게 맞춘다 — 여기만 느슨하면 재설정을 통해 정책보다 약한 비밀번호가
 * 들어올 수 있다. 상한 64자는 BCrypt가 72바이트를 넘는 입력을 조용히 잘라내기 때문이다.
 */
public record PasswordResetConfirmRequest(
        @NotBlank String token,
        @NotBlank @Size(min = 8, max = 64) String newPassword
) {
}
