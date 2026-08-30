package com.safedeal.domain.auth.controller;

import com.safedeal.domain.auth.dto.LoginRequest;
import com.safedeal.domain.auth.dto.SignupRequest;
import com.safedeal.domain.auth.dto.TokenResponse;
import com.safedeal.domain.auth.service.AuthCommandService;
import com.safedeal.domain.auth.service.AuthTokens;
import com.safedeal.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;

/**
 * 인증 API — 회원가입(AUTH-1)·로그인(AUTH-2·AUTH-8).
 *
 * <b>토큰 전달 규칙</b>(정책 '쿠키 전달'): access는 응답 바디로 주고 프런트가 메모리에만
 * 보관한다(localStorage 금지). refresh는 httpOnly + Secure + SameSite=Lax 쿠키로만 나가며,
 * 경로를 /api/v1/auth로 좁혀 일반 데이터 요청에는 아예 실리지 않게 한다.
 *
 * 재발급·로그아웃은 다음 커밋에서, 이메일 인증·비밀번호 찾기와 OAuth는 후속 PR에서 추가된다.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String REFRESH_COOKIE = "refreshToken";
    private static final String COOKIE_PATH = "/api/v1/auth";

    private final AuthCommandService authCommandService;

    /** 회원가입 (AUTH-1). 정책상 가입 즉시 로그인 상태로 진입하므로 토큰까지 함께 준다. */
    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<TokenResponse>> signup(@Valid @RequestBody SignupRequest request) {
        AuthTokens tokens = authCommandService.signup(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.SET_COOKIE, refreshCookie(tokens).toString())
                .body(ApiResponse.success(TokenResponse.from(tokens.access())));
    }

    /** 로그인 (AUTH-2). 브루트포스 잠금이 계정+IP 기준이라 클라이언트 IP가 필요하다(AUTH-8). */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<TokenResponse>> login(@Valid @RequestBody LoginRequest request,
                                                            HttpServletRequest servletRequest) {
        AuthTokens tokens = authCommandService.login(request, clientIp(servletRequest));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(tokens).toString())
                .body(ApiResponse.success(TokenResponse.from(tokens.access())));
    }

    private ResponseCookie refreshCookie(AuthTokens tokens) {
        Duration maxAge = Duration.between(Instant.now(), tokens.refresh().expiresAt());
        return baseCookie(tokens.refresh().value(), maxAge.isNegative() ? Duration.ZERO : maxAge);
    }


    private ResponseCookie baseCookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)          // XSS로도 못 읽는다 - '안전'이 아니라 피해 반경 축소
                .secure(true)            // 브라우저는 http://localhost도 보안 컨텍스트로 취급해 로컬 개발에 지장 없다
                .sameSite("Lax")         // Strict는 OAuth 리다이렉트에서 쿠키가 안 실려 못 쓴다
                .path(COOKIE_PATH)       // 재발급 경로에만 실린다
                .maxAge(maxAge)
                .build();
    }


    /**
     * 클라이언트 IP.
     *
     * X-Forwarded-For를 신뢰하지 않는다 — 클라이언트가 마음대로 넣을 수 있어서 헤더만 바꿔가며
     * 브루트포스 잠금을 무한히 회피할 수 있다. ALB 뒤에 붙어 실제 IP가 필요해지면 그때
     * 신뢰 프록시 설정(ForwardedHeaderFilter)을 함께 넣어야 한다.
     */
    private String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
