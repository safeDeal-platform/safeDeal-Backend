package com.safedeal.domain.auth.service;

import com.safedeal.domain.auth.client.KakaoOauthClient;
import com.safedeal.domain.auth.client.KakaoProfile;
import com.safedeal.domain.auth.entity.OauthAccount;
import com.safedeal.domain.auth.entity.OauthProvider;
import com.safedeal.domain.auth.exception.AuthErrorCode;
import com.safedeal.domain.auth.repository.OauthAccountRepository;
import com.safedeal.domain.auth.repository.OauthTicketStore;
import com.safedeal.domain.auth.repository.OauthTicketStore.SignupTicket;
import com.safedeal.domain.user.entity.User;
import com.safedeal.domain.user.repository.UserRepository;
import com.safedeal.global.exception.BusinessException;
import com.safedeal.global.exception.CommonErrorCode;
import com.safedeal.global.util.EmailNormalizer;
import com.safedeal.global.util.SecureToken;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 카카오 로그인 (AUTH-5). 흐름은 셋이다.
 * <ol>
 *   <li>{@link #start} — state를 만들어 카카오 동의 화면으로 보낸다.</li>
 *   <li>{@link #callback} — 카카오가 돌려보낸 요청. 이미 연결된 사용자는 로그인, 처음 온 사용자는 가입 티켓을 받는다.</li>
 *   <li>{@link #completeSignup} — 처음 온 사용자가 닉네임을 내면 계정을 만든다.</li>
 * </ol>
 *
 * 2026-11-19 결정: 카카오가 인증한 이메일이 없으면 가입을 받지 않는다(AUTH014). 같은 이메일의 로컬
 * 계정은 그 계정도 이메일 인증을 마쳤을 때만 자동으로 연결한다(AUTH015) — 미인증 계정에 붙이면 남의
 * 이메일로 먼저 가입해 둔 사람이 피해자 계정에 비밀번호로도 들어갈 수 있다.
 */
@Service
@RequiredArgsConstructor
public class OauthCommandService {

    private static final OauthProvider PROVIDER = OauthProvider.KAKAO;
    private static final String ACCESS_DENIED = "access_denied";

    /** state·가입 티켓 쿠키 수명. Redis 보관 시간과 같아야 한다 — 쿠키가 더 길면 만료된 값을 들고 오고, 짧으면 멀쩡한 로그인이 끊긴다. */
    public static final Duration COOKIE_TTL = OauthTicketStore.TTL;

    private final TransactionTemplate transactionTemplate;
    private final KakaoOauthClient kakaoOauthClient;
    private final OauthTicketStore ticketStore;
    private final OauthAccountRepository oauthAccountRepository;
    private final UserRepository userRepository;
    private final AuthCommandService authCommandService;

    /** 카카오로 보낼 주소와, 같은 브라우저인지 대조하려고 쿠키에도 심을 state. */
    public record Start(String state, URI authorizeUri) {
    }

    /** 콜백 결과 — 둘 중 하나만 채워진다. 로그인이면 토큰, 처음 온 사용자면 가입 티켓. */
    public record CallbackResult(AuthTokens tokens, String signupTicket) {
    }

    public Start start() {
        String state = SecureToken.generate();
        ticketStore.saveState(state);
        return new Start(state, kakaoOauthClient.authorizeUri(state));
    }

    /**
     * 카카오 콜백.
     *
     * state 검사를 맨 먼저, 카카오 호출보다 앞에 둔다 — 남이 만든 콜백 주소를 피해자 브라우저가 열게
     * 하는 공격(로그인 CSRF)은 여기서 끝나야 한다. 쿠키(이 브라우저가 시작했나)와 Redis(한 번만 쓰였나)를
     * 둘 다 본다. 쿠키만 보면 재사용을, Redis만 보면 남의 state를 못 막는다.
     *
     * 트랜잭션은 카카오 호출이 끝난 뒤 DB 작업에만 건다 — 카카오 응답(최대 십수 초)을 기다리는 동안
     * DB 연결을 붙잡고 있으면, 카카오가 느려질 때 연결 풀이 말라 다른 API까지 멈춘다.
     *
     * @param error 사용자가 동의 화면에서 취소하면 카카오가 access_denied를 채워 보낸다
     */
    public CallbackResult callback(String code, String state, String stateCookie, String error) {
        if (state == null || stateCookie == null
                || !MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8),
                        stateCookie.getBytes(StandardCharsets.UTF_8))
                || !ticketStore.consumeState(state)) {
            throw new BusinessException(AuthErrorCode.OAUTH_INVALID_STATE);
        }
        if (error != null) {
            // 취소만 AUTH013 — server_error 같은 카카오 쪽 오류를 "취소하셨어요"로 안내하면 안 된다.
            throw new BusinessException(ACCESS_DENIED.equals(error)
                    ? AuthErrorCode.OAUTH_CANCELLED : AuthErrorCode.OAUTH_PROVIDER_FAILED);
        }
        if (code == null || code.isBlank()) {
            throw new BusinessException(AuthErrorCode.OAUTH_PROVIDER_FAILED);
        }

        KakaoProfile profile = kakaoOauthClient.fetchProfile(code);
        return transactionTemplate.execute(status -> resolveAccount(profile));
    }

    private CallbackResult resolveAccount(KakaoProfile profile) {
        String providerUserId = String.valueOf(profile.id());

        // 이미 연결된 카카오 계정이면 이메일은 보지 않는다 — 매칭 키는 (provider, 회원번호)다.
        Optional<OauthAccount> linked = oauthAccountRepository.findByProviderAndProviderUserId(PROVIDER, providerUserId);
        if (linked.isPresent()) {
            User user = userRepository.findByIdAndDeletedAtIsNull(linked.get().getUserId())
                    .filter(User::isLoginAllowed)
                    .orElseThrow(() -> new BusinessException(AuthErrorCode.ACCOUNT_NOT_ACTIVE));
            return new CallbackResult(login(user), null);
        }

        String email = profile.verifiedEmail()
                .map(EmailNormalizer::normalize)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.OAUTH_EMAIL_REQUIRED));

        Optional<User> sameEmail = userRepository.findByEmailAndDeletedAtIsNull(email);
        if (sameEmail.isPresent()) {
            // DB 콜레이션(ai_ci)은 악센트만 다른 주소(gmaïl.com ↔ gmail.com)도 같은 행으로 찾아준다(D-18).
            // 글자까지 같을 때만 같은 사람으로 본다 — 아니면 비슷한 주소로 먼저 가입한 사람에게 카카오가 붙는다.
            if (!EmailNormalizer.normalize(sameEmail.get().getEmail()).equals(email)) {
                throw new BusinessException(AuthErrorCode.OAUTH_LINK_UNAVAILABLE);
            }
            return linkAndLogin(sameEmail.get(), providerUserId);
        }
        // 탈퇴한 계정이 이메일 UNIQUE를 계속 쥐고 있으면 새 계정을 만들 수 없다(User.deletedAt 주석).
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(AuthErrorCode.OAUTH_LINK_UNAVAILABLE);
        }

        String ticket = SecureToken.generate();
        ticketStore.saveSignupTicket(ticket, providerUserId, email);
        return new CallbackResult(null, ticket);
    }

    /**
     * 처음 온 카카오 사용자의 가입 마무리.
     *
     * 티켓은 계정 INSERT가 DB에 반영된(flush) <b>뒤에</b> 소진한다 — 닉네임 중복처럼 흔한 실패에서는
     * 티켓이 살아 있어 같은 티켓으로 다시 낼 수 있다. 같은 티켓으로 동시에 두 번 와도 이메일 UNIQUE와
     * DEL 반환값이 하나만 통과시킨다.
     */
    @Transactional
    public AuthTokens completeSignup(String ticket, String nickname) {
        if (ticket == null || ticket.isBlank()) {
            throw new BusinessException(AuthErrorCode.INVALID_OAUTH_SIGNUP_TICKET);
        }
        SignupTicket saved = ticketStore.findSignupTicket(ticket)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.INVALID_OAUTH_SIGNUP_TICKET));

        if (userRepository.existsByNickname(nickname)) {
            throw new BusinessException(AuthErrorCode.DUPLICATE_NICKNAME);
        }
        // 티켓을 받은 뒤 같은 이메일로 로컬 가입이 끼어든 경우.
        if (userRepository.existsByEmail(saved.email())) {
            throw new BusinessException(AuthErrorCode.DUPLICATE_EMAIL);
        }

        User user;
        try {
            user = userRepository.saveAndFlush(User.createOauth(saved.email(), nickname));
            oauthAccountRepository.saveAndFlush(OauthAccount.link(user.getId(), PROVIDER, saved.providerUserId()));
        } catch (DataIntegrityViolationException e) {
            // 동시에 들어온 다른 요청이 같은 이메일·닉네임·카카오 계정을 먼저 차지했다.
            throw new BusinessException(CommonErrorCode.CONCURRENT_MODIFICATION);
        }
        if (!ticketStore.consumeSignupTicket(ticket)) {
            // 다른 요청이 먼저 소진했다 — 예외로 이 트랜잭션의 INSERT를 되돌린다.
            throw new BusinessException(AuthErrorCode.INVALID_OAUTH_SIGNUP_TICKET);
        }
        return login(user);
    }

    private CallbackResult linkAndLogin(User user, String providerUserId) {
        if (!user.isEmailVerified()) {
            throw new BusinessException(AuthErrorCode.OAUTH_LINK_UNAVAILABLE);
        }
        // 카카오 소유가 증명된 본인이라 제재 사실을 알려도 된다(2026-11-19 결정, 로컬 로그인도 비밀번호 확인 뒤 같은 코드).
        if (!user.isLoginAllowed()) {
            throw new BusinessException(AuthErrorCode.ACCOUNT_NOT_ACTIVE);
        }
        // 이미 다른 카카오 계정이 붙어 있다 — 유저당 공급자 하나(uk_oauth_accounts_user_provider).
        if (oauthAccountRepository.existsByUserIdAndProvider(user.getId(), PROVIDER)) {
            throw new BusinessException(AuthErrorCode.OAUTH_LINK_UNAVAILABLE);
        }
        try {
            oauthAccountRepository.saveAndFlush(OauthAccount.link(user.getId(), PROVIDER, providerUserId));
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(CommonErrorCode.CONCURRENT_MODIFICATION);
        }
        return new CallbackResult(login(user), null);
    }

    private AuthTokens login(User user) {
        user.markLoggedIn(Instant.now());
        return authCommandService.issueTokens(user);
    }
}
