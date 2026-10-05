package com.safedeal.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 로그인 요청 (AUTH-2). 가입과 달리 형식 검증을 세밀하게 하지 않는다 — "이메일 형식이 아니다"처럼
 * 자세히 알려주면 그 자체가 계정 존재 여부 정보가 된다. 값이 있는지만 보고 나머지는 같은 실패로 묶는다.
 */
public record LoginRequest(
        @NotBlank String email,
        @NotBlank String password
) {
}
