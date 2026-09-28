package com.safedeal.domain.auth.controller;

import com.safedeal.domain.auth.dto.EmailVerifyRequest;
import com.safedeal.domain.auth.dto.LoginRequest;
import com.safedeal.domain.auth.dto.PasswordResetConfirmRequest;
import com.safedeal.domain.auth.dto.PasswordResetRequest;
import com.safedeal.domain.auth.dto.AuthResponse;
import com.safedeal.domain.auth.dto.SignupRequest;
import com.safedeal.domain.auth.dto.TokenResponse;
import com.safedeal.domain.auth.exception.AuthErrorCode;
import com.safedeal.domain.auth.service.AuthCommandService;
import com.safedeal.domain.auth.service.EmailVerificationService;
import com.safedeal.domain.auth.service.PasswordResetService;
import com.safedeal.domain.auth.service.AuthTokens;
import com.safedeal.global.exception.BusinessException;
import com.safedeal.global.security.AuthenticatedUser;
import com.safedeal.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;

/**
 * 인증 API — 회원가입·로그인·재발급·로그아웃 (AUTH-1 ~ AUTH-4, AUTH-8).
 *
 * access 토큰은 응답 바디로, refresh 토큰은 httpOnly 쿠키로만 내려준다(정책 '쿠키 전달') —
 * 자바스크립트가 읽을 수 있는 곳에 refresh를 두면 탈취당했을 때 그대로 도난당한다.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String REFRESH_COOKIE = "refreshToken";
    private static final String COOKIE_PATH = "/api/auth";
    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthCommandService authCommandService;
    private final EmailVerificationService emailVerificationService;
    private final PasswordResetService passwordResetService;

    /**
     * 회원가입 (AUTH-1). 가입하면 바로 로그인 상태가 되므로 토큰도 함께 준다.
     * 클라이언트 IP가 필요한 이유: 주소를 바꿔가며 가입을 반복하면 인증 메일 발송 한도를 다 써버릴 수 있어서다.
     */
    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<AuthResponse>> signup(@Valid @RequestBody SignupRequest request,
                                                             HttpServletRequest servletRequest) {
        AuthTokens tokens = authCommandService.signup(request, clientIp(servletRequest));
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.SET_COOKIE, refreshCookie(tokens).toString())
                .body(ApiResponse.success(AuthResponse.from(tokens)));
    }

    /** 로그인 (AUTH-2). 브루트포스 잠금이 계정+IP 기준이라 클라이언트 IP가 필요하다(AUTH-8). */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request,
                                                            HttpServletRequest servletRequest) {
        AuthTokens tokens = authCommandService.login(request, clientIp(servletRequest));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(tokens).toString())
                .body(ApiResponse.success(AuthResponse.from(tokens)));
    }

    /** 토큰 재발급 (AUTH-3). 프런트의 silent refresh가 여기로 온다. */
    @PostMapping("/reissue")
    public ResponseEntity<ApiResponse<TokenResponse>> reissue(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(AuthErrorCode.REFRESH_TOKEN_MISSING);
        }
        AuthTokens tokens = authCommandService.reissue(refreshToken);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(tokens).toString())
                .body(ApiResponse.success(TokenResponse.from(tokens.access())));
    }

    /**
     * 로그아웃 (AUTH-4). access가 만료된 뒤에도 호출돼야 하므로 인증을 요구하지 않는다.
     * 서버 쪽 처리가 실패해도 쿠키는 반드시 지운다 — 남으면 공용 PC에서 다음 사람이 자동 로그인될 수 있다.
     */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            HttpServletRequest servletRequest) {
        authCommandService.logout(bearerToken(servletRequest), refreshToken);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, expiredRefreshCookie().toString())
                .body(ApiResponse.success());
    }

    /**
     * 이메일 인증 완료 (AUTH-6). 인증을 요구하지 않는다 — 메일 링크를 누르는 기기가 가입한
     * 기기와 다를 수 있어서다(PC에서 가입, 폰에서 클릭). 토큰 자체가 본인 확인 역할을 한다.
     */
    @PostMapping("/email/verify")
    public ResponseEntity<ApiResponse<Void>> verifyEmail(@Valid @RequestBody EmailVerifyRequest request) {
        emailVerificationService.verify(request.token());
        return ResponseEntity.ok(ApiResponse.success());
    }

    /**
     * 인증 메일 재발송 (AUTH-6). 반대로 인증을 요구한다 — 이메일만 받아 보내주면 남의 주소로
     * 메일을 대신 쏘는 발송기가 되고, 응답으로 가입 여부까지 확인할 수 있게 된다.
     */
    @PostMapping("/email/verify/resend")
    public ResponseEntity<ApiResponse<Void>> resendVerificationMail(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        emailVerificationService.resendTo(principal.userId());
        return ResponseEntity.ok(ApiResponse.success());
    }

    /**
     * 비밀번호 재설정 링크 요청 (AUTH-7). 계정이 없거나 OAuth 전용이어도 항상 같은 200을 준다 —
     * 응답이 다르면 로그인 없이 가입 여부를 확인하는 통로가 된다(정책).
     */
    @PostMapping("/password/reset-request")
    public ResponseEntity<ApiResponse<Void>> requestPasswordReset(
            @Valid @RequestBody PasswordResetRequest request, HttpServletRequest servletRequest) {
        passwordResetService.requestReset(request.email(), clientIp(servletRequest));
        return ResponseEntity.ok(ApiResponse.success());
    }

    /**
     * 새 비밀번호 설정 (AUTH-7). 성공하면 그 유저의 refresh 토큰을 전부 무효화한다. 기존
     * access 토큰은 만료 전까지 유효할 수 있다.
     */
    @PostMapping("/password/reset")
    public ResponseEntity<ApiResponse<Void>> resetPassword(
            @Valid @RequestBody PasswordResetConfirmRequest request) {
        passwordResetService.resetPassword(request.token(), request.newPassword());
        return ResponseEntity.ok(ApiResponse.success());
    }

    private ResponseCookie refreshCookie(AuthTokens tokens) {
        Duration maxAge = Duration.between(Instant.now(), tokens.refresh().expiresAt());
        return baseCookie(tokens.refresh().value(), maxAge.isNegative() ? Duration.ZERO : maxAge);
    }

    private ResponseCookie expiredRefreshCookie() {
        return baseCookie("", Duration.ZERO);
    }

    private ResponseCookie baseCookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)          // XSS로도 못 읽는다 - '안전'이 아니라 피해 반경 축소
                .secure(true)            // 브라우저는 http://localhost도 보안 컨텍스트로 취급해 로컬 개발에 지장 없다
                .sameSite("Lax")         // Strict는 OAuth 리다이렉트에서 쿠키가 안 실려 못 쓴다
                .path(COOKIE_PATH)       // 재발급·로그아웃 경로에만 실린다
                .maxAge(maxAge)
                .build();
    }

    private String bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        return header.substring(BEARER_PREFIX.length()).trim();
    }

    /**
     * 클라이언트 IP. X-Forwarded-For는 신뢰하지 않는다 — 클라이언트가 헤더값을 마음대로 바꿔
     * 로그인 잠금을 무한히 피해갈 수 있어서다.
     */
    private String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
