package com.safedeal.domain.auth.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.nio.charset.StandardCharsets;

/**
 * 재설정 링크로 새 비밀번호를 설정한다 (AUTH-7). 길이 제약은 회원가입과 동일하다 — 여기만
 * 느슨하면 재설정으로 정책보다 약한 비밀번호가 들어올 수 있다.
 */
public record PasswordResetConfirmRequest(
        @NotBlank String token,
        @NotBlank @Size(min = 8, max = 64) String newPassword
) {

    /** {@link SignupRequest#isPasswordWithinBcryptLimit()}와 같은 제약 — 사유도 같다. */
    @AssertTrue(message = "비밀번호가 너무 깁니다. 한글은 한 글자가 3바이트로 계산됩니다.")
    public boolean isNewPasswordWithinBcryptLimit() {
        return newPassword == null
                || newPassword.getBytes(StandardCharsets.UTF_8).length <= SignupRequest.BCRYPT_MAX_BYTES;
    }
}
