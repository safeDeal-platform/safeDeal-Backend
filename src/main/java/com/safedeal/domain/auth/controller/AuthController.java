package com.safedeal.domain.auth.controller;

import com.safedeal.domain.auth.dto.EmailVerifyRequest;
import com.safedeal.domain.auth.dto.LoginRequest;
import com.safedeal.domain.auth.dto.OauthSignupRequest;
import com.safedeal.domain.auth.dto.PasswordResetConfirmRequest;
import com.safedeal.domain.auth.dto.PasswordResetRequest;
import com.safedeal.domain.auth.dto.AuthResponse;
import com.safedeal.domain.auth.dto.SignupRequest;
import com.safedeal.domain.auth.dto.TokenResponse;
import com.safedeal.domain.auth.exception.AuthErrorCode;
import com.safedeal.domain.auth.client.KakaoOauthProperties;
import com.safedeal.domain.auth.repository.OauthTicketStore;
import com.safedeal.domain.auth.service.AuthCommandService;
import com.safedeal.domain.auth.service.OauthCommandService;
import com.safedeal.domain.auth.service.OauthCommandService.CallbackResult;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

/**
 * 인증 API — 회원가입·로그인·재발급·로그아웃 (AUTH-1 ~ AUTH-4, AUTH-8), 카카오 로그인 (AUTH-5).
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

    // 카카오 로그인 중에만 쓰는 쿠키 두 개. 경로를 /api/auth/oauth로 좁혀 다른 요청에는 실리지 않게 한다.
    private static final String OAUTH_STATE_COOKIE = "oauthState";
    private static final String OAUTH_SIGNUP_COOKIE = "oauthSignup";
    private static final String OAUTH_COOKIE_PATH = "/api/auth/oauth";

    private final AuthCommandService authCommandService;
    private final EmailVerificationService emailVerificationService;
    private final PasswordResetService passwordResetService;
    private final OauthCommandService oauthCommandService;
    private final KakaoOauthProperties kakaoOauthProperties;

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

    /**
     * 카카오 로그인 시작 (AUTH-5). 프런트는 이 주소로 화면을 이동시키기만 하면 된다.
     * state를 쿠키에도 심어 콜백이 "이 브라우저가 시작한 로그인"인지 확인한다(로그인 CSRF 방지).
     */
    @GetMapping("/oauth/kakao")
    public ResponseEntity<Void> startKakaoLogin() {
        OauthCommandService.Start start = oauthCommandService.start();
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(start.authorizeUri())
                .header(HttpHeaders.SET_COOKIE,
                        oauthCookie(OAUTH_STATE_COOKIE, start.state(), OauthTicketStore.TTL).toString())
                .build();
    }

    /**
     * 카카오 콜백 (AUTH-5). 브라우저 이동이라 JSON을 줄 수 없어, 결과를 프런트 주소의 쿼리로 넘긴다.
     * <ul>
     *   <li>로그인 — refresh 쿠키 + {@code ?result=login}. access는 프런트가 /reissue로 받는다(정책 '토큰 전달').</li>
     *   <li>처음 온 사용자 — 가입 티켓 쿠키 + {@code ?result=signup}. 프런트가 닉네임을 받아 /oauth/kakao/signup으로 낸다.</li>
     *   <li>실패 — {@code ?error=AUTH0xx}. 에러 코드 외에는 아무것도 붙이지 않는다.</li>
     * </ul>
     * 이동할 주소는 요청값이 아니라 설정값 하나로 고정한다 — 요청으로 받으면 남의 사이트로 보내는 통로(오픈 리다이렉트)가 된다.
     * state 쿠키는 결과와 상관없이 지운다(1회용).
     */
    @GetMapping("/oauth/kakao/callback")
    public ResponseEntity<Void> kakaoCallback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            @CookieValue(name = OAUTH_STATE_COOKIE, required = false) String stateCookie) {
        String clearState = oauthCookie(OAUTH_STATE_COOKIE, "", Duration.ZERO).toString();
        try {
            CallbackResult result = oauthCommandService.callback(code, state, stateCookie, error);
            if (result.tokens() != null) {
                return redirectToFrontend("result", "login", clearState, refreshCookie(result.tokens()).toString());
            }
            return redirectToFrontend("result", "signup", clearState,
                    oauthCookie(OAUTH_SIGNUP_COOKIE, result.signupTicket(), OauthTicketStore.TTL).toString());
        } catch (BusinessException e) {
            return redirectToFrontend("error", e.getErrorCode().getCode(), clearState);
        }
    }

    /**
     * 카카오로 처음 온 사용자의 가입 마무리 (AUTH-5). 응답은 로컬 회원가입과 같다.
     * 닉네임이 겹치면(AUTH002) 가입 티켓이 살아 있어 같은 쿠키로 다시 낼 수 있다.
     */
    @PostMapping("/oauth/kakao/signup")
    public ResponseEntity<ApiResponse<AuthResponse>> completeKakaoSignup(
            @Valid @RequestBody OauthSignupRequest request,
            @CookieValue(name = OAUTH_SIGNUP_COOKIE, required = false) String ticket) {
        AuthTokens tokens = oauthCommandService.completeSignup(ticket, request.nickname());
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.SET_COOKIE,
                        refreshCookie(tokens).toString(),
                        oauthCookie(OAUTH_SIGNUP_COOKIE, "", Duration.ZERO).toString())
                .body(ApiResponse.success(AuthResponse.from(tokens)));
    }

    private ResponseEntity<Void> redirectToFrontend(String key, String value, String... cookies) {
        URI target = UriComponentsBuilder.fromUriString(kakaoOauthProperties.getFrontendCallbackUrl())
                .queryParam(key, value)
                .encode()
                .build()
                .toUri();
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(target)
                .header(HttpHeaders.SET_COOKIE, cookies)
                .build();
    }

    /** 카카오 로그인 중에만 쓰는 1회용 쿠키. 속성은 refresh 쿠키와 같고 경로·수명만 다르다. */
    private ResponseCookie oauthCookie(String name, String value, Duration maxAge) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")         // 카카오에서 돌아오는 최상위 GET 이동에도 실린다
                .path(OAUTH_COOKIE_PATH)
                .maxAge(maxAge)
                .build();
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
