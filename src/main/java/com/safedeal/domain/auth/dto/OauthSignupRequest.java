package com.safedeal.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 카카오로 처음 온 사용자의 가입 마무리 (AUTH-5). 닉네임은 카카오 값을 쓰지 않고 직접 받는다
 * (A-7 다안). 규칙은 로컬 가입(SignupRequest)과 같다. 카카오 정보는 바디가 아니라 가입 티켓 쿠키로 온다.
 */
public record OauthSignupRequest(
        @NotBlank @Size(min = 2, max = 20) String nickname
) {
}
