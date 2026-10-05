package com.safedeal.global.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

// 서명 키는 기본값을 두지 않는다 — 기본값이 있으면 JWT_SECRET을 빠뜨려도 조용히 기동해
// 누구나 토큰을 위조할 수 있게 된다(운영에서는 RequiredPropertyGuard가 한 번 더 확인).
@Component
@Validated
@Getter
@Setter
@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {

    @NotBlank
    private String secret;

    // access 30분 · refresh 3일 (정책 '경화 — JWT'). 값 자체는 yml에 둔다.
    @NotNull
    private Duration accessTtl;

    @NotNull
    private Duration refreshTtl;
}
