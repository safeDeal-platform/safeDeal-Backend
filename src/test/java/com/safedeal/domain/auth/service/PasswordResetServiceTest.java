package com.safedeal.domain.auth.service;

import com.safedeal.domain.auth.entity.PasswordResetToken;
import com.safedeal.domain.auth.exception.AuthErrorCode;
import com.safedeal.domain.auth.repository.PasswordResetTokenRepository;
import com.safedeal.domain.user.entity.User;
import com.safedeal.domain.user.repository.UserRepository;
import com.safedeal.global.exception.BusinessException;
import com.safedeal.global.security.JwtTokenProvider;
import com.safedeal.global.util.SecureToken;
import com.safedeal.testsupport.IntegrationTestSupport;
import com.safedeal.testsupport.RecordingMailSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 재설정 사용(resetPassword)의 잠금 순서와 계정 거부 가드, 소진 시점 만료 재검사, 토큰 인덱스를 고정한다.
 * 잠금 순서는 동시 실행으로는 bcrypt 시간 때문에 한쪽 순서만 재현돼, 호출 순서를 직접 검증한다.
 */
class PasswordResetServiceTest extends IntegrationTestSupport {

    private static final String OLD_PASSWORD = "password123";

    @Autowired PasswordResetService passwordResetService;
    @MockitoSpyBean PasswordResetTokenRepository passwordResetTokenRepository;
    @MockitoSpyBean UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired RecordingMailSender mailSender;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    private User user;

    @BeforeEach
    void setUp() {
        long n = System.nanoTime();
        user = userRepository.save(
                User.createLocal("reset-guard-" + n + "@test.com", passwordEncoder.encode(OLD_PASSWORD), "가드" + n));
        Set<String> keys = redisTemplate.keys("auth:mail-rate:v1:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        mailSender.clear();
    }

    @AfterEach
    void clear() {
        passwordResetTokenRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    private PasswordResetToken seedToken(String raw) {
        return passwordResetTokenRepository.save(PasswordResetToken.issue(
                user.getId(), JwtTokenProvider.hash(raw), Instant.now().plus(Duration.ofMinutes(30))));
    }

    private String storedPasswordHash() {
        return jdbc.queryForObject("select password_hash from users where id = ?", String.class, user.getId());
    }

    private static boolean isInvalidResetToken(Throwable t) {
        return t instanceof BusinessException e && e.getErrorCode() == AuthErrorCode.INVALID_PASSWORD_RESET_TOKEN;
    }

    @Test
    @DisplayName("재설정 사용은 토큰을 소진하기 전에 유저 행을 먼저 잠근다 - 발급(requestReset)과 같은 순서")
    void resetLocksUserRowBeforeConsumingToken() {
        String raw = SecureToken.generate();
        PasswordResetToken token = seedToken(raw);

        passwordResetService.resetPassword(raw, "newPassword1");

        InOrder order = inOrder(userRepository, passwordResetTokenRepository);
        order.verify(userRepository).findForPasswordResetUpdate(user.getId());
        order.verify(passwordResetTokenRepository).markUsed(eq(token.getId()), any(Instant.class));
        assertThat(passwordEncoder.matches("newPassword1", storedPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("링크 발급 뒤 탈퇴한 계정은 그 링크로 비밀번호를 바꿀 수 없고 링크도 소진되지 않는다")
    void withdrawnAccountCannotReset() {
        String raw = SecureToken.generate();
        PasswordResetToken token = seedToken(raw);
        jdbc.update("update users set deleted_at = ? where id = ?", java.sql.Timestamp.from(Instant.now()), user.getId());

        assertThatThrownBy(() -> passwordResetService.resetPassword(raw, "newPassword1"))
                .matches(PasswordResetServiceTest::isInvalidResetToken);

        assertThat(passwordEncoder.matches(OLD_PASSWORD, storedPasswordHash())).isTrue();
        assertThat(passwordResetTokenRepository.findById(token.getId()).orElseThrow().getUsedAt()).isNull();
    }

    @Test
    @DisplayName("비밀번호가 없는 소셜 전용 계정은 링크로 비밀번호를 만들 수 없다")
    void oauthOnlyAccountCannotReset() {
        String raw = SecureToken.generate();
        seedToken(raw);
        jdbc.update("update users set password_hash = null where id = ?", user.getId());

        assertThatThrownBy(() -> passwordResetService.resetPassword(raw, "newPassword1"))
                .matches(PasswordResetServiceTest::isInvalidResetToken);

        assertThat(storedPasswordHash()).isNull();
    }

    @Test
    @DisplayName("소셜 전용 계정이 재설정을 요청하면 링크 대신 소셜 로그인 안내 메일만 받는다")
    void oauthOnlyAccountRequestGetsGuidanceWithoutLink() {
        jdbc.update("update users set password_hash = null where id = ?", user.getId());

        passwordResetService.requestReset(user.getEmail(), "127.0.0.1");

        verify(passwordResetTokenRepository, never()).markAllUsedByUserId(anyLong(), any(Instant.class));
        assertThat(passwordResetTokenRepository.count()).isZero();
        String body = mailSender.lastTo(user.getEmail()).orElseThrow().body();
        assertThat(body).contains("소셜 로그인").doesNotContain("?token=");
    }

    @Test
    @DisplayName("소진 시점에 이미 만료된 링크는 소진되지 않는다 - 첫 검사 뒤 잠금 대기 중 만료된 경우")
    void markUsedRejectsTokenExpiredAtConsumption() {
        PasswordResetToken token = seedToken(SecureToken.generate());

        Integer updated = tx.execute(status ->
                passwordResetTokenRepository.markUsed(token.getId(), token.getExpiresAt().plusSeconds(1)));

        assertThat(updated).isZero();
        assertThat(passwordResetTokenRepository.findById(token.getId()).orElseThrow().getUsedAt()).isNull();
    }

    @Test
    @DisplayName("첫 검사 때 유효했던 링크가 유저 행 잠금을 기다리는 사이 만료되면 비밀번호를 바꾸지 않는다")
    void tokenExpiringWhileWaitingForLockIsRejected() throws Exception {
        String raw = SecureToken.generate();
        Instant expiresAt = Instant.now().plusMillis(1500);
        passwordResetTokenRepository.save(PasswordResetToken.issue(user.getId(), JwtTokenProvider.hash(raw), expiresAt));

        // 다른 트랜잭션이 유저 행을 잠근 채 만료 시각이 지날 때까지 붙잡는다.
        CountDownLatch locked = new CountDownLatch(1);
        ExecutorService holder = Executors.newSingleThreadExecutor();
        try {
            Future<?> hold = holder.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.queryForObject("select id from users where id = ? for update", Long.class, user.getId());
                locked.countDown();
                try {
                    Thread.sleep(Math.max(0, Duration.between(Instant.now(), expiresAt).toMillis() + 300));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> passwordResetService.resetPassword(raw, "newPassword1"))
                    .matches(PasswordResetServiceTest::isInvalidResetToken);
            hold.get(10, TimeUnit.SECONDS);
        } finally {
            holder.shutdownNow();
        }

        assertThat(passwordEncoder.matches(OLD_PASSWORD, storedPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("토큰 테이블에 user_id 인덱스가 있다 - 발급 시 옛 링크 소진이 전체를 훑지 않게")
    void userIdIndexExists() {
        Integer count = jdbc.queryForObject(
                "select count(*) from information_schema.statistics where table_schema = database()"
                        + " and table_name = 'password_reset_tokens' and index_name = 'idx_password_reset_tokens_user_id'"
                        + " and column_name = 'user_id' and seq_in_index = 1",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
