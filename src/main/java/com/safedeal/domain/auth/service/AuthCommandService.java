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
     * 존재하지 않는 계정에 로그인 시도가 왔을 때 대조할 더미 해시. 계정이 없다고 바로 실패시키면
     * 비밀번호 비교를 건너뛰어 응답 속도만으로 가입 여부가 드러난다 — 없어도 같은 비용의 비교를 한 번 한다.
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
     * 회원가입 (AUTH-1). 가입하면 바로 로그인 상태가 되므로 토큰까지 발급한다. 이메일 중복 검사는
     * 소프트 삭제된 행도 포함한다 — email UNIQUE 제약이 삭제된 행도 그대로 잡고 있어서, 검사 범위가
     * 좁으면 검사는 통과하고 INSERT에서 터진다.
     */
    @Transactional
    public AuthTokens signup(SignupRequest request, String clientIp) {
        // 계정을 만들기 전에 먼저 센다 — 가입 한 번이 메일 한 통이라, 제한 없으면 다른 사용자 메일까지 막힐 수 있다.
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

        // 가입은 성공시키고 인증만 미룬다 — 미인증 상태로도 로그인·열람은 가능하다. 발송 실패는 예외로 올리지 않는다(MailSender 참고).
        emailVerificationService.sendVerificationMail(user);
        return issueTokens(user);
    }

    /**
     * 로그인 (AUTH-2). 비밀번호 대조를 먼저 하고 계정 상태(제재·탈퇴)는 그다음에 본다 — 순서를
     * 뒤집으면 비밀번호를 모르는 사람도 "이 계정은 정지됐다"는 걸 알아낼 수 있다.
     */
    @Transactional
    public AuthTokens login(LoginRequest request, String clientIp) {
        String accountKey = JwtTokenProvider.hash(request.email());
        if (loginAttemptStore.isLocked(accountKey, clientIp)) {
            throw new BusinessException(AuthErrorCode.TOO_MANY_LOGIN_ATTEMPTS);
        }

        User user = userRepository.findByEmailAndDeletedAtIsNull(request.email()).orElse(null);

        // 계정이 없거나 OAuth 전용이어도 비교를 건너뛰지 않는다 — 건너뛰면 응답 속도로 가입 여부가 드러난다.
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
     * 토큰 재발급 (AUTH-3) — RTR + 화이트리스트 + 재사용 감지. 서명은 통과했는데 화이트리스트에
     * 없으면 이미 한 번 쓰인 토큰이 다시 온 것 — 탈취본이 돌아다닌다는 신호라 그 유저의 모든 기기를 끊는다.
     */
    @Transactional
    public AuthTokens reissue(String refreshToken) {
        var claims = tokenProvider.resolveRefresh(refreshToken)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.INVALID_TOKEN));

        var entry = refreshTokenStore.find(claims.userId(), claims.jti()).orElse(null);
        if (entry == null) {
            log.warn("refresh 재사용 감지 - 해당 유저의 모든 세션을 무효화한다. userId={}", claims.userId());
            // 무효화 실패는 그대로 터뜨린다(500) — 401로 감추면 공격자의 나머지 기기 토큰은 그대로 살아 있는데 성공한 것처럼 보인다.
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

        // RTR: 직전 토큰을 원자적으로 소진하고 실제로 지운 요청만 새 토큰을 받는다(RefreshTokenStore.consume 참고).
        if (!refreshTokenStore.consume(claims.userId(), claims.jti())) {
            throw new BusinessException(AuthErrorCode.INVALID_TOKEN);
        }
        return issueTokens(user);
    }

    /**
     * 로그아웃 (AUTH-4). access·refresh가 없거나 무효화 쓰기가 실패해도 성공으로 처리한다 —
     * 여기서 막아봐야 얻는 보안은 없고, 쿠키만 남아 공용 PC에서 다음 사람이 로그인되는 더 나쁜 결과가 된다.
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
        // 저장 실패는 그대로 올린다(fail-closed) — 화이트리스트에 없는 refresh를 내주면 다음 재발급 때 재사용 공격으로 오판된다.
        refreshTokenStore.save(user.getId(), refresh.jti(),
                JwtTokenProvider.hash(refresh.value()), refresh.expiresAt());
        return new AuthTokens(user, access, refresh);
    }
}
