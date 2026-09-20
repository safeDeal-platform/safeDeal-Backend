package com.safedeal.domain.auth.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.nio.charset.StandardCharsets;

/**
 * 로컬 회원가입 요청 (AUTH-1).
 *
 * 비밀번호 하한 8자는 브루트포스 방어의 "근본 방어는 비밀번호 정책"에 해당한다(정책 AUTH-8).
 * 상한은 BCrypt가 72바이트를 넘는 입력을 조용히 잘라내기 때문에 둔다 - 상한이 없으면
 * 긴 비밀번호를 쓴 사용자가 사실상 앞 72바이트만으로 인증된다는 걸 모르게 된다.
 */
public record SignupRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(min = 8, max = 64) String password,
        @NotBlank @Size(min = 2, max = 20) String nickname
) {

    /** BCrypt가 처리하는 최대 길이. 글자 수가 아니라 UTF-8 바이트 수다. */
    static final int BCRYPT_MAX_BYTES = 72;

    /**
     * 글자 수 상한(64자)만으로는 부족하다 — 한글은 UTF-8에서 글자당 3바이트라 64자가
     * 192바이트가 되고, BCrypt는 72바이트에서 잘라낸다. 잘린 뒷부분은 비밀번호를 바꿔도
     * 로그인이 되는 형태로 조용히 무시되므로, 400으로 분명히 거절한다.
     * ({@link PasswordResetConfirmRequest}에도 같은 제약이 있어야 재설정으로 우회되지 않는다.)
     */
    @AssertTrue(message = "비밀번호가 너무 깁니다. 한글은 한 글자가 3바이트로 계산됩니다.")
    public boolean isPasswordWithinBcryptLimit() {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= BCRYPT_MAX_BYTES;
    }
}
