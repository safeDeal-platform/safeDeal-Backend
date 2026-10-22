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
import com.safedeal.global.util.EmailNormalizer;
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
     *
     * 이메일은 들어오자마자 정규화한다(trim+소문자) — 레이트리밋 키와 계정 조회가 같은 문자열을
     * 봐야, 대소문자만 바꿔 한도를 우회하는 공격이 안 통한다(D-17, 악센트 변형은 D-18로 남김).
     */
    @Transactional
    public void requestReset(String email, String clientIp) {
        String normalizedEmail = EmailNormalizer.normalize(email);
        // 계정을 보기 전에 먼저 센다 — 존재하는 주소만 센다면 429 여부로 가입 여부가 드러난다.
        if (!mailSendRateLimiter.allowPasswordResetRequest(JwtTokenProvider.hash(normalizedEmail), clientIp)) {
            throw new BusinessException(AuthErrorCode.TOO_MANY_MAIL_REQUESTS);
        }
        // 존재 확인은 잠금 없이 한다 — email(UNIQUE 보조 인덱스)로 바로 잠그면, 가입 안 한
        // 이메일로 매번 호출되는 이 분기에서 InnoDB가 그 갭에 gap lock을 걸어 같은 이메일로
        // 들어오는 회원가입 INSERT를 블록할 수 있다(CodeRabbit·L1 리뷰 지적, PR #40).
        Optional<User> found = userRepository.findByEmailAndDeletedAtIsNull(normalizedEmail);
        if (found.isEmpty()) {
            return;
        }
        // 존재가 확인된 뒤에만 id(클러스터 PK)로 다시 잠가 조회한다 — PK 조회는 행이 있으면
        // 레코드 잠금만 걸려 gap lock 문제가 없다. 이 잠금이 아래 소진·발급 전체를 직렬화해,
        // 같은 유저가 동시에 두 번 요청해도 "최신 링크 1개" 보장이 깨지지 않게 한다.
        User user = userRepository.findForPasswordResetUpdate(found.get().getId()).orElse(null);
        if (user == null) {
            return;
        }

        if (!user.hasPassword()) {
            MailDispatch.afterCommit(mailSender, user.getEmail(), SUBJECT,
                    "이 계정은 소셜 로그인(구글/카카오)으로 만들어져 재설정할 비밀번호가 없습니다.\n"
                            + "가입에 사용한 소셜 계정으로 로그인해 주세요.");
            return;
        }

        // 새 링크를 만들기 전에 옛 미사용 링크를 전부 소진한다(D-15) — save보다 먼저 와야
        // 방금 만든 새 토큰이 스스로 걸리지 않는다.
        Instant now = Instant.now();
        tokenRepository.markAllUsedByUserId(user.getId(), now);

        String rawToken = SecureToken.generate();
        tokenRepository.save(PasswordResetToken.issue(
                user.getId(), JwtTokenProvider.hash(rawToken), now.plus(TTL)));

        String link = mailProperties.getBaseUrl() + "/auth/password/reset?token=" + rawToken;
        // 커밋 후 발송 — 토큰 행이 롤백되면 열리지 않는 링크를 보내는 셈이 된다.
        MailDispatch.afterCommit(mailSender, user.getEmail(), SUBJECT,
                "아래 링크에서 새 비밀번호를 설정해 주세요. 링크는 30분 동안 유효합니다.\n"
                        + "새 링크를 요청하면 이전 링크는 사용할 수 없습니다.\n" + link);
    }

    /**
     * 새 비밀번호를 설정하고 해당 유저의 refresh 토큰을 전부 무효화한다. 기존 access 토큰은
     * 만료 전까지 유효할 수 있다.
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
