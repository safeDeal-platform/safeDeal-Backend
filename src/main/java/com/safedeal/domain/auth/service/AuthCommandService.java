package com.safedeal.domain.auth.service;

import com.safedeal.domain.auth.dto.LoginRequest;
import com.safedeal.domain.auth.dto.SignupRequest;
import com.safedeal.domain.auth.exception.AuthErrorCode;
import com.safedeal.domain.auth.repository.LoginAttemptStore;
import com.safedeal.domain.auth.repository.MailSendRateLimiter;
import com.safedeal.domain.auth.repository.RefreshTokenStore;
import com.safedeal.domain.user.entity.User;
import com.safedeal.domain.user.repository.UserRepository;
import com.safedeal.global.exception.BusinessException;
import com.safedeal.global.security.JwtTokenProvider;
import com.safedeal.global.security.JwtTokenProvider.IssuedToken;
import com.safedeal.global.security.TokenBlacklist;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * 인증 명령 (AUTH-1 회원가입 / AUTH-2 로그인 / AUTH-3 재발급 / AUTH-4 로그아웃).
 */
@Slf4j
@Service
public class AuthCommandService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final RefreshTokenStore refreshTokenStore;
    private final TokenBlacklist tokenBlacklist;
    private final LoginAttemptStore loginAttemptStore;
    private final MailSendRateLimiter mailSendRateLimiter;
    private final EmailVerificationService emailVerificationService;

    /**
     * 존재하지 않는 계정으로 로그인을 시도했을 때 대조할 더미 해시.
     *
     * 계정이 없을 때 즉시 실패시키면 BCrypt 비교(수십 ms)를 건너뛰게 되어 응답 시간만으로
     * "이 이메일은 가입돼 있다/없다"를 구분할 수 있다. 에러 코드를 같게 맞춰도 시간이 새면
     * 열거 방지가 반쪽이 되므로, 계정이 없어도 같은 비용의 비교를 한 번 수행한다.
     */
    private final String dummyPasswordHash;

    public AuthCommandService(UserRepository userRepository,
                              PasswordEncoder passwordEncoder,
                              JwtTokenProvider tokenProvider,
                              RefreshTokenStore refreshTokenStore,
                              TokenBlacklist tokenBlacklist,
                              LoginAttemptStore loginAttemptStore,
                              MailSendRateLimiter mailSendRateLimiter,
                              EmailVerificationService emailVerificationService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.refreshTokenStore = refreshTokenStore;
        this.tokenBlacklist = tokenBlacklist;
        this.loginAttemptStore = loginAttemptStore;
        this.mailSendRateLimiter = mailSendRateLimiter;
        this.emailVerificationService = emailVerificationService;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * 회원가입 (AUTH-1). 정책상 <b>가입 즉시 로그인 상태로 진입</b>하므로 토큰까지 발급한다.
     *
     * 이메일 중복 검사는 소프트 삭제된 행까지 포함한다 — 정책이 "동일 이메일 재가입 1차 불허"이고
     * DB의 email UNIQUE도 삭제 행을 점유하므로, 사전 검사 범위가 다르면 검사만 통과하고
     * INSERT에서 터진다.
     */
    @Transactional
    public AuthTokens signup(SignupRequest request, String clientIp) {
        // 계정을 만들기 전에 센다. 가입 한 번이 인증 메일 한 통이라, 이 경로만 열려 있으면
        // 주소를 바꿔가며 반복 호출해 공급자 쿼터를 태우고 다른 사용자의 메일까지 멈춘다.
        if (!mailSendRateLimiter.allowSignup(clientIp)) {
            throw new BusinessException(AuthErrorCode.TOO_MANY_MAIL_REQUESTS);
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new BusinessException(AuthErrorCode.DUPLICATE_EMAIL);
        }
        if (userRepository.existsByNickname(request.nickname())) {
            throw new BusinessException(AuthErrorCode.DUPLICATE_NICKNAME);
        }

        User user = userRepository.save(User.createLocal(
                request.email(), passwordEncoder.encode(request.password()), request.nickname()));
        user.markLoggedIn(Instant.now());

        // 가입 자체는 성공시키고 인증만 미룬다 - 정책상 미인증도 로그인·열람은 가능하고
        // OAuth 자동연결 대상에서만 빠진다. 발송 실패는 예외로 올리지 않는다(MailSender 주석).
        emailVerificationService.sendVerificationMail(user);
        return issueTokens(user);
    }

    /**
     * 로그인 (AUTH-2).
     *
     * 순서가 중요하다. 비밀번호 대조를 <b>먼저</b> 하고 계정 상태(제재·탈퇴)는 그다음에 본다 —
     * 순서를 뒤집으면 비밀번호를 모르는 사람도 "이 계정은 정지됐다"는 사실을 알아낼 수 있다.
     */
    @Transactional
    public AuthTokens login(LoginRequest request, String clientIp) {
        String accountKey = JwtTokenProvider.hash(request.email());
        if (loginAttemptStore.isLocked(accountKey, clientIp)) {
            throw new BusinessException(AuthErrorCode.TOO_MANY_LOGIN_ATTEMPTS);
        }

        User user = userRepository.findByEmailAndDeletedAtIsNull(request.email()).orElse(null);

        // 계정이 없거나 OAuth 전용이어도 대조를 건너뛰지 않는다. 건너뛰면 BCrypt 비용(수십 ms)이
        // 빠져 응답 시간만으로 "이 이메일은 가입돼 있다/없다"가 드러난다 — 에러 코드를 같게
        // 맞춰도 시간이 새면 열거 방지가 반쪽이 된다. 항상 정확히 한 번 비교한다.
        boolean hasPassword = user != null && user.hasPassword();
        String hashToCompare = hasPassword ? user.getPasswordHash() : dummyPasswordHash;
        boolean passwordMatches = passwordEncoder.matches(request.password(), hashToCompare);

        if (!hasPassword || !passwordMatches) {
            loginAttemptStore.recordFailure(accountKey, clientIp);
            throw new BusinessException(AuthErrorCode.INVALID_CREDENTIALS);
        }
        if (!user.isLoginAllowed()) {
            throw new BusinessException(AuthErrorCode.ACCOUNT_NOT_ACTIVE);
        }

        loginAttemptStore.clear(accountKey, clientIp);
        user.markLoggedIn(Instant.now());
        return issueTokens(user);
    }

    /**
     * 토큰 재발급 (AUTH-3) — RTR + 화이트리스트 + 재사용 감지.
     *
     * 서명이 통과해도 화이트리스트에 없으면 <b>이미 한 번 쓰인 토큰</b>이라는 뜻이다. 정상
     * 사용자라면 재발급 때 직전 토큰이 폐기되고 새 토큰을 받았을 테니, 옛 토큰이 다시 오는 것은
     * 탈취본이 돌아다닌다는 신호다. 그래서 그 유저의 모든 기기를 끊는다(정책).
     */
    @Transactional
    public AuthTokens reissue(String refreshToken) {
        var claims = tokenProvider.resolveRefresh(refreshToken)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.INVALID_TOKEN));

        var entry = refreshTokenStore.find(claims.userId(), claims.jti()).orElse(null);
        if (entry == null) {
            log.warn("refresh 재사용 감지 - 해당 유저의 모든 세션을 무효화한다. userId={}", claims.userId());
            // 무효화가 실패하면 여기서 그대로 터진다(500). 401로 바꿔 내리면 "차단했다"는 응답이
            // 나가지만 공격자의 나머지 기기 토큰은 살아 있다 — 실패를 실패로 보여야 한다.
            refreshTokenStore.revokeAll(claims.userId());
            throw new BusinessException(AuthErrorCode.INVALID_TOKEN);
        }

        Instant now = Instant.now();
        if (!entry.tokenHash().equals(JwtTokenProvider.hash(refreshToken)) || entry.isExpired(now)) {
            // 만료는 키 TTL이 아니라 저장된 expiresAt으로 판정한다(RefreshTokenStore 주석 참고).
            refreshTokenStore.revoke(claims.userId(), claims.jti());
            throw new BusinessException(AuthErrorCode.INVALID_TOKEN);
        }

        User user = userRepository.findById(claims.userId())
                .filter(User::isLoginAllowed)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.INVALID_TOKEN));

        // RTR: 직전 토큰을 원자적으로 소진하고, 실제로 지운 요청만 새 토큰을 받는다.
        // find로 확인하고 revoke로 지우면 같은 refresh가 동시에 두 번 들어왔을 때 둘 다
        // 통과해 토큰 하나에서 유효한 세션이 둘 생긴다 — HDEL의 반환값이 심판이다.
        if (!refreshTokenStore.consume(claims.userId(), claims.jti())) {
            throw new BusinessException(AuthErrorCode.INVALID_TOKEN);
        }
        return issueTokens(user);
    }

    /**
     * 로그아웃 (AUTH-4).
     *
     * access·refresh 모두 <b>없어도 성공</b>으로 처리한다. access가 만료된 뒤에도 로그아웃은
     * 돼야 하고, Redis 쓰기가 실패해도 마찬가지다 — 여기서 실패를 돌려주면 보안은 하나도 못
     * 얻으면서(쓰기가 이미 실패했으므로 무효화는 어차피 안 된다) 사용자만 막고, 쿠키가 남아
     * 공용 PC에서 다음 사람이 남의 계정에 로그인되는 더 나쁜 결과가 된다. 쿠키 삭제는 컨트롤러가
     * 응답 헤더로 항상 수행한다.
     */
    public void logout(String accessToken, String refreshToken) {
        if (accessToken != null) {
            tokenProvider.resolveAccess(accessToken)
                    .ifPresent(claims -> tokenBlacklist.add(claims.jti(), claims.expiresAt()));
        }
        if (refreshToken != null) {
            tokenProvider.resolveRefresh(refreshToken)
                    .ifPresent(claims -> refreshTokenStore.revoke(claims.userId(), claims.jti()));
        }
    }

    private AuthTokens issueTokens(User user) {
        IssuedToken access = tokenProvider.issueAccess(user.getId(), user.getRole().name());
        IssuedToken refresh = tokenProvider.issueRefresh(user.getId());
        // 저장 실패는 그대로 올린다(fail-closed). 화이트리스트에 없는 refresh를 발급하면
        // 그 사용자는 다음 재발급 때 재사용 공격으로 판정돼 전 기기 로그아웃당한다.
        refreshTokenStore.save(user.getId(), refresh.jti(),
                JwtTokenProvider.hash(refresh.value()), refresh.expiresAt());
        return new AuthTokens(user, access, refresh);
    }
}
