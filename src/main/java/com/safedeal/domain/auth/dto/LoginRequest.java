package com.safedeal.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 로그인 요청 (AUTH-2).
 *
 * 형식 제약을 가입만큼 걸지 않는 이유: 로그인에서 "이메일 형식이 아니다"처럼 세밀하게
 * 되돌려주면 응답 차이 자체가 정보가 된다. 값 유무만 보고 나머지는 전부 같은 실패로 수렴시킨다.
 */
public record LoginRequest(
        @NotBlank String email,
        @NotBlank String password
) {
}
