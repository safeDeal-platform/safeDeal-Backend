package com.safedeal.domain.auth.service;

import com.safedeal.domain.auth.entity.PasswordResetToken;
import com.safedeal.domain.auth.exception.AuthErrorCode;
import com.safedeal.domain.auth.repository.MailSendRateLimiter;
import com.safedeal.domain.auth.repository.PasswordResetTokenRepository;
import com.safedeal.domain.auth.repository.RefreshTokenStore;
import com.safedeal.domain.user.entity.User;
import com.safedeal.domain.user.repository.UserRepository;
import com.safedeal.global.exception.BusinessException;
import com.safedeal.global.mail.MailDispatch;
import com.safedeal.global.mail.MailProperties;
import com.safedeal.global.mail.MailSender;
import com.safedeal.global.security.JwtTokenProvider;
import com.safedeal.global.util.SecureToken;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 비밀번호 찾기 (AUTH-7) — 재설정 링크 TTL 30분, 1회용. 이메일 인증(24시간)보다 짧게 두는
 * 이유는 이 링크 하나로 계정을 통째로 가져갈 수 있어서다.
 */
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final Duration TTL = Duration.ofMinutes(30);
    private static final String SUBJECT = "[SafeDeal] 비밀번호 재설정 안내";

    private final PasswordResetTokenRepository tokenRepository;
    private final MailSendRateLimiter mailSendRateLimiter;
    private final UserRepository userRepository;
    private final RefreshTokenStore refreshTokenStore;
    private final PasswordEncoder passwordEncoder;
    private final MailSender mailSender;
    private final MailProperties mailProperties;

    /**
     * 재설정 링크를 보낸다. 계정이 없거나 OAuth 전용이어도 예외 없이 항상 같은 200을 준다 —
     * 응답이 다르면 이 API가 가입 여부 조회기가 된다.
     *
     * OAuth 전용 계정에는 재설정 링크 대신 복구 안내를 메일 본문으로 보낸다 — 화면에 안내를
     * 띄우면 그 자체가 열거 정보가 된다(정책).
     */
    @Transactional
    public void requestReset(String email, String clientIp) {
        // 계정을 보기 전에 먼저 센다 — 존재하는 주소만 센다면 429 여부로 가입 여부가 드러난다.
        if (!mailSendRateLimiter.allowPasswordResetRequest(JwtTokenProvider.hash(email), clientIp)) {
            throw new BusinessException(AuthErrorCode.TOO_MANY_MAIL_REQUESTS);
        }
        Optional<User> found = userRepository.findByEmailAndDeletedAtIsNull(email);
        if (found.isEmpty()) {
            return;
        }
        User user = found.get();

        if (!user.hasPassword()) {
            MailDispatch.afterCommit(mailSender, user.getEmail(), SUBJECT,
                    "이 계정은 소셜 로그인(구글/카카오)으로 만들어져 재설정할 비밀번호가 없습니다.\n"
                            + "가입에 사용한 소셜 계정으로 로그인해 주세요.");
            return;
        }

        String rawToken = SecureToken.generate();
        tokenRepository.save(PasswordResetToken.issue(
                user.getId(), JwtTokenProvider.hash(rawToken), Instant.now().plus(TTL)));

        String link = mailProperties.getBaseUrl() + "/auth/password/reset?token=" + rawToken;
        // 커밋 후 발송 — 토큰 행이 롤백되면 열리지 않는 링크를 보내는 셈이 된다.
        MailDispatch.afterCommit(mailSender, user.getEmail(), SUBJECT,
                "아래 링크에서 새 비밀번호를 설정해 주세요. 링크는 30분 동안 유효합니다.\n" + link);
    }

    /**
     * 새 비밀번호를 설정하고 해당 유저의 로그인 세션을 전부 끊는다(정책) — 비밀번호를 바꾸는
     * 상황은 대개 계정을 뺏겼을 때라, 세션까지 안 끊으면 공격자가 다른 기기에 그대로 남는다.
     */
    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        Instant now = Instant.now();
        PasswordResetToken token = tokenRepository.findByTokenHash(JwtTokenProvider.hash(rawToken))
                .filter(candidate -> candidate.isUsable(now))
                .orElseThrow(() -> new BusinessException(AuthErrorCode.INVALID_PASSWORD_RESET_TOKEN));

        User user = userRepository.findByIdAndDeletedAtIsNull(token.getUserId())
                .filter(User::hasPassword)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.INVALID_PASSWORD_RESET_TOKEN));

        // 1회용 판정은 조건부 UPDATE가 한다 — 동시에 두 번 제출해도 영향 행이 1인 요청만 통과한다.
        if (tokenRepository.markUsed(token.getId(), now) != 1) {
            throw new BusinessException(AuthErrorCode.INVALID_PASSWORD_RESET_TOKEN);
        }
        user.changePassword(passwordEncoder.encode(newPassword));
        refreshTokenStore.revokeAll(user.getId());
    }
}
