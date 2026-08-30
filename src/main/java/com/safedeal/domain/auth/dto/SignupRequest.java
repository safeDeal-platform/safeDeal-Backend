package com.safedeal.domain.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 로컬 회원가입 요청 (AUTH-1).
 *
 * 비밀번호 하한 8자는 브루트포스 방어의 "근본 방어는 비밀번호 정책"에 해당한다(정책 AUTH-8).
 * 상한 64자는 BCrypt가 72바이트를 넘는 입력을 조용히 잘라내기 때문에 둔다 - 상한이 없으면
 * 긴 비밀번호를 쓴 사용자가 사실상 앞 72바이트만으로 인증된다는 걸 모르게 된다.
 */
public record SignupRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(min = 8, max = 64) String password,
        @NotBlank @Size(min = 2, max = 20) String nickname
) {
}
