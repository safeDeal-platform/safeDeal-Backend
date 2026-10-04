package com.safedeal.domain.auth.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.nio.charset.StandardCharsets;

/**
 * 로컬 회원가입 요청 (AUTH-1). 비밀번호 최소 8자는 무차별 대입 공격 방어 정책이다(AUTH-8).
 * 최대 64자는 BCrypt가 72바이트 넘는 입력을 조용히 잘라내기 때문에 두는 상한이다.
 */
public record SignupRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(min = 8, max = 64) String password,
        @NotBlank @Size(min = 2, max = 20) String nickname
) {

    /** BCrypt가 처리하는 최대 길이. 글자 수가 아니라 UTF-8 바이트 수다. */
    static final int BCRYPT_MAX_BYTES = 72;

    /**
     * 한글은 글자당 3바이트라 64자가 BCrypt 한도(72바이트)를 넘을 수 있다 — 넘으면 뒷부분이 조용히
     * 잘려 다른 비밀번호로도 로그인되므로 400으로 명확히 거절한다({@link PasswordResetConfirmRequest}도 동일).
     */
    @AssertTrue(message = "비밀번호가 너무 깁니다. 한글은 한 글자가 3바이트로 계산됩니다.")
    public boolean isPasswordWithinBcryptLimit() {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= BCRYPT_MAX_BYTES;
    }
}
