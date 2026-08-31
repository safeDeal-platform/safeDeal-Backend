package com.safedeal.domain.auth.service;

import com.safedeal.domain.auth.entity.EmailVerificationToken;
import com.safedeal.domain.auth.exception.AuthErrorCode;
import com.safedeal.domain.auth.repository.EmailVerificationTokenRepository;
import com.safedeal.domain.auth.repository.MailSendRateLimiter;
import com.safedeal.domain.user.entity.User;
import com.safedeal.domain.user.repository.UserRepository;
import com.safedeal.global.exception.BusinessException;
import com.safedeal.global.mail.MailProperties;
import com.safedeal.global.mail.MailSender;
import com.safedeal.global.security.JwtTokenProvider;
import com.safedeal.global.util.SecureToken;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * 이메일 인증 (AUTH-6).
 *
 * 가입 시 email_verified=false로 만들고 인증 토큰(해시 저장, TTL 24h, 1회용)을 메일로 보낸다.
 * 이 플로우가 필요한 이유는 OAuth 자동연결 조건이 email_verified=true인데, 로컬 가입에서 그걸
 * true로 만들 경로가 없으면 자동연결이 영영 불가능하거나 미검증 이메일을 자동연결하는
 * 계정 탈취 벡터가 생기기 때문이다(정책).
 *
 * MVP는 미인증도 로그인·열람이 가능하다 — 인증 배지가 없고 OAuth 자동연결 대상에서만 빠진다.
 */
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private static final Duration TTL = Duration.ofHours(24);
    private static final String SUBJECT = "[SafeDeal] 이메일 인증을 완료해 주세요";

    private final EmailVerificationTokenRepository tokenRepository;
    private final MailSendRateLimiter mailSendRateLimiter;
    private final UserRepository userRepository;
    private final MailSender mailSender;
    private final MailProperties mailProperties;

    /**
     * 인증 토큰을 만들어 메일로 보낸다. 가입 직후와 재발송 요청에서 함께 쓴다.
     *
     * 이미 인증된 계정이면 아무것도 하지 않는다 — 인증이 끝난 뒤에도 링크를 계속 발급하면
     * 살아 있는 링크가 늘어나기만 한다.
     */
    @Transactional
    public void sendVerificationMail(User user) {
        if (user.isEmailVerified()) {
            return;
        }
        String rawToken = SecureToken.generate();
        tokenRepository.save(EmailVerificationToken.issue(
                user.getId(), JwtTokenProvider.hash(rawToken), Instant.now().plus(TTL)));

        String link = mailProperties.getBaseUrl() + "/auth/email/verify?token=" + rawToken;
        mailSender.send(user.getEmail(), SUBJECT,
                "아래 링크를 눌러 이메일 인증을 완료해 주세요. 링크는 24시간 동안 유효합니다.\n" + link);
    }

    /**
    /**
     * 로그인한 본인에게 인증 메일을 다시 보낸다 (재발송 API).
     *
     * 유저 조회를 컨트롤러가 아니라 여기서 하는 이유: 컨트롤러가 리포지토리를 직접 들면
     * "누구에게 보낼지"를 정하는 규칙이 서비스 밖으로 새어 나간다. 이 API의 핵심 제약이
     * 바로 그 규칙(본인에게만)이라 서비스 안에 있어야 한다.
     */
    @Transactional
    public void resendTo(Long userId) {
        if (!mailSendRateLimiter.allowVerificationResend(userId)) {
            throw new BusinessException(AuthErrorCode.TOO_MANY_MAIL_REQUESTS);
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.INVALID_TOKEN));
        sendVerificationMail(user);
    }

    /**
     * 링크의 원문 토큰을 검증하고 계정을 인증 상태로 바꾼다.
     *
     * 만료·사용됨·없음을 모두 같은 에러로 묶는 이유: 셋을 구분해 주면 "이 토큰은 존재하지만
     * 만료됐다" 같은 정보가 새어 토큰 추측에 힌트가 된다.
     */
    @Transactional
    public void verify(String rawToken) {
        Instant now = Instant.now();
        EmailVerificationToken token = tokenRepository.findByTokenHash(JwtTokenProvider.hash(rawToken))
                .filter(candidate -> candidate.isUsable(now))
                .orElseThrow(() -> new BusinessException(AuthErrorCode.INVALID_EMAIL_VERIFICATION_TOKEN));

        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new BusinessException(AuthErrorCode.INVALID_EMAIL_VERIFICATION_TOKEN));

        token.markUsed(now);
        user.verifyEmail();
    }
}
