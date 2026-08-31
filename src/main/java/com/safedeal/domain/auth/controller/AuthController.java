package com.safedeal.domain.auth.controller;

import com.safedeal.domain.auth.dto.EmailVerifyRequest;
import com.safedeal.domain.auth.dto.LoginRequest;
import com.safedeal.domain.auth.dto.PasswordResetConfirmRequest;
import com.safedeal.domain.auth.dto.PasswordResetRequest;
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
 * <b>토큰 전달 규칙</b>(정책 '쿠키 전달'): access는 응답 바디로 주고 프런트가 메모리에만
 * 보관한다(localStorage 금지). refresh는 httpOnly + Secure + SameSite=Lax 쿠키로만 나가며,
 * 경로를 /api/v1/auth로 좁혀 일반 데이터 요청에는 아예 실리지 않게 한다.
 *
 * OAuth(AUTH-5)는 카카오 앱키가 나오는 대로 이 컨트롤러에 추가된다.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String REFRESH_COOKIE = "refreshToken";
    private static final String COOKIE_PATH = "/api/v1/auth";
    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthCommandService authCommandService;
    private final EmailVerificationService emailVerificationService;
    private final PasswordResetService passwordResetService;

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
     * 로그아웃 (AUTH-4).
     *
     * 인증을 요구하지 않는다 — access가 만료된 뒤에도 로그아웃은 돼야 하기 때문이다.
     * 그리고 서버 측 무효화(Redis 쓰기) 성공 여부와 무관하게 <b>쿠키는 반드시 지우고 200을
     * 반환한다</b>. 쿠키를 남기면 사용자가 로그아웃 실패로 보고 브라우저만 닫고 떠났을 때,
     * 다음 사람이 그 브라우저의 silent refresh로 남의 계정에 로그인된다(공용 PC).
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
     * 이메일 인증 완료 (AUTH-6).
     *
     * 인증을 요구하지 않는다 - 메일 링크를 누르는 시점에 그 브라우저가 로그인 상태라는
     * 보장이 없다(PC에서 가입하고 폰 메일함에서 누르는 경우). 토큰 자체가 본인 확인이다.
     */
    @PostMapping("/email/verify")
    public ResponseEntity<ApiResponse<Void>> verifyEmail(@Valid @RequestBody EmailVerifyRequest request) {
        emailVerificationService.verify(request.token());
        return ResponseEntity.ok(ApiResponse.success());
    }

    /**
     * 인증 메일 재발송 (AUTH-6).
     *
     * 여기는 반대로 인증이 필요하다. 이메일만 받아 재발송해 주면 응답으로 가입 여부를
     * 확인할 수 있고, 남의 주소로 메일을 대신 쏘는 발송기가 된다. 본인 계정에만 보낸다.
     */
    @PostMapping("/email/verification")
    public ResponseEntity<ApiResponse<Void>> resendVerificationMail(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        emailVerificationService.resendTo(principal.userId());
        return ResponseEntity.ok(ApiResponse.success());
    }

    /**
     * 비밀번호 재설정 링크 요청 (AUTH-7).
     *
     * 계정이 없어도, OAuth 전용이어도 <b>똑같은 200</b>을 준다. 응답이 달라지는 순간 이
     * API는 로그인 없이 쓸 수 있는 가입 여부 조회기가 된다. 소셜 계정이라는 안내는 화면이
     * 아니라 메일 본문으로 보낸다 - 진짜 주인만 읽을 수 있는 경로다(정책).
     */
    @PostMapping("/password/reset-request")
    public ResponseEntity<ApiResponse<Void>> requestPasswordReset(
            @Valid @RequestBody PasswordResetRequest request) {
        passwordResetService.requestReset(request.email());
        return ResponseEntity.ok(ApiResponse.success());
    }

    /**
     * 새 비밀번호 설정 (AUTH-7).
     *
     * 성공하면 그 유저의 refresh가 전부 끊긴다. 비밀번호를 바꾸는 상황은 대개 계정을
     * 빼앗겼을 때인데, 공격자가 다른 기기에 로그인해 있으면 비밀번호만 바꿔봐야
     * 그 세션이 그대로 살아 있다.
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
