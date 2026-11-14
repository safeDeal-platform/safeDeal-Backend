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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 비밀번호 재설정의 동시 실행 — 같은 유저의 요청 연타, 요청과 사용의 겹침, 같은 링크 이중 제출,
 * 서로 다른 유저의 동시 요청에서 "최신 링크 1개"와 1회용이 지켜지고 잠금 교착이 나지 않는지 확인한다.
 * 잠금 순서·격리수준 근거는 PasswordResetService 주석에 있고, 잠금 순서 자체는 PasswordResetServiceTest가 고정한다.
 *
 * {@code @Transactional}을 붙이지 않는 이유는 FavoriteConcurrencyTest와 같다 — 별도
 * 스레드는 테스트 트랜잭션 밖이라 롤백 대상이 아니고, 롤백 안에 갇히면 경합이 재현되지 않는다.
 */
class PasswordResetConcurrencyTest extends IntegrationTestSupport {

    /** 레이트리밋(주소당 시간당 3회)에 걸리지 않게 그 안에서 동시성을 재현한다. */
    private static final int THREADS = 3;

    /** 경합 타이밍은 확률적이라 라운드를 반복해 재현 확률을 높인다(보장이 아니라 회귀 방지용). */
    private static final int ROUNDS = 5;

    private static final String OLD_PASSWORD = "password123";

    @Autowired PasswordResetService passwordResetService;
    @Autowired PasswordResetTokenRepository passwordResetTokenRepository;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired StringRedisTemplate redisTemplate;

    private String email;
    private Long userId;

    @BeforeEach
    void setUp() {
        User user = newUser();
        email = user.getEmail();
        userId = user.getId();

        // 다른 테스트가 같은 공유 Redis에 남긴 한도 카운터가 이 테스트의 3회를 잡아먹지 않게 비운다.
        Set<String> keys = redisTemplate.keys("auth:mail-rate:v1:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @AfterEach
    void clear() {
        passwordResetTokenRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    private User newUser() {
        long n = System.nanoTime();
        return userRepository.save(
                User.createLocal("reset-race-" + n + "@test.com", passwordEncoder.encode(OLD_PASSWORD), "동시요청" + n));
    }

    /** 사용 전 상태의 재설정 토큰을 심고 원문을 돌려준다(DB에는 해시만 저장된다). */
    private String seedToken(Long ownerId) {
        String raw = SecureToken.generate();
        passwordResetTokenRepository.save(PasswordResetToken.issue(
                ownerId, JwtTokenProvider.hash(raw), Instant.now().plus(Duration.ofMinutes(30))));
        return raw;
    }

    private long validTokenCount(Long ownerId) {
        return passwordResetTokenRepository.findAll().stream()
                .filter(token -> token.getUserId().equals(ownerId))
                .filter(token -> token.getUsedAt() == null)
                .count();
    }

    private String storedPasswordHash(Long ownerId) {
        return userRepository.findById(ownerId).orElseThrow().getPasswordHash();
    }

    private static boolean isInvalidResetToken(Throwable t) {
        return t instanceof BusinessException e && e.getErrorCode() == AuthErrorCode.INVALID_PASSWORD_RESET_TOKEN;
    }

    /** 모든 작업을 같은 순간에 풀어 경합 창을 넓힌다. 실패는 삼키지 않고 그대로 올린다. */
    private List<Throwable> runConcurrently(List<Runnable> actions) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(actions.size());
        ExecutorService pool = Executors.newFixedThreadPool(actions.size());
        try {
            List<Callable<Throwable>> tasks = new ArrayList<>();
            for (Runnable action : actions) {
                tasks.add(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        action.run();
                        return null;
                    } catch (Throwable t) {
                        return t;
                    }
                });
            }
            List<Throwable> failures = new ArrayList<>();
            for (Future<Throwable> future : pool.invokeAll(tasks)) {
                Throwable t = future.get();
                if (t != null) {
                    failures.add(t);
                }
            }
            return failures;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("같은 유저가 재설정을 동시에 여러 번 요청해도 유효한 링크는 하나만 남는다")
    void concurrentRequestsLeaveExactlyOneValidToken() throws Exception {
        List<Throwable> failures = runConcurrently(
                Collections.nCopies(THREADS, () -> passwordResetService.requestReset(email, "127.0.0.1")));

        assertThat(failures)
                .withFailMessage("동시 재설정 요청에서 예외가 났다: %s", failures)
                .isEmpty();

        long validCount = validTokenCount(userId);
        assertThat(validCount)
                .withFailMessage("유효한 토큰이 1개가 아니라 %d개 남았다 - '최신 링크 1개' 보장이 깨졌다", validCount)
                .isEqualTo(1);
    }

    @Test
    @DisplayName("옛 링크로 비밀번호를 바꾸는 순간 새 링크를 요청해도 교착 없이 끝나고 유효한 링크는 하나다")
    void resetAndRequestAtOnceDoNotDeadlock() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            User user = newUser();
            String raw = seedToken(user.getId());
            String newPassword = "newPassword" + round;
            String ip = "10.0.1." + round;

            List<Throwable> failures = runConcurrently(List.of(
                    () -> passwordResetService.resetPassword(raw, newPassword),
                    () -> passwordResetService.requestReset(user.getEmail(), ip)));

            // 요청이 먼저 커밋하면 옛 링크가 소진돼 사용 쪽이 AUTH009로 지는 것은 정상이다. 그 밖의 예외(교착 포함)는 실패다.
            assertThat(failures)
                    .withFailMessage("라운드 %d: 예상 밖 예외 %s", round, failures)
                    .hasSizeLessThanOrEqualTo(1)
                    .allMatch(PasswordResetConcurrencyTest::isInvalidResetToken);
            assertThat(validTokenCount(user.getId()))
                    .withFailMessage("라운드 %d: 유효한 토큰이 1개가 아니다", round)
                    .isEqualTo(1);

            String expected = failures.isEmpty() ? newPassword : OLD_PASSWORD;
            assertThat(passwordEncoder.matches(expected, storedPasswordHash(user.getId())))
                    .withFailMessage("라운드 %d: 비밀번호가 결과(%s)와 맞지 않는다",
                            round, failures.isEmpty() ? "변경됨" : "그대로")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("같은 링크를 동시에 두 번 제출하면 한 번만 성공하고 비밀번호는 그 요청의 값이다")
    void sameTokenSubmittedTwiceSucceedsOnce() throws Exception {
        String raw = seedToken(userId);

        List<Throwable> failures = runConcurrently(List.of(
                () -> passwordResetService.resetPassword(raw, "firstPassword1"),
                () -> passwordResetService.resetPassword(raw, "secondPassword2")));

        assertThat(failures)
                .withFailMessage("정확히 한 요청만 AUTH009로 져야 한다: %s", failures)
                .hasSize(1)
                .allMatch(PasswordResetConcurrencyTest::isInvalidResetToken);

        String hash = storedPasswordHash(userId);
        assertThat(passwordEncoder.matches("firstPassword1", hash) ^ passwordEncoder.matches("secondPassword2", hash))
                .withFailMessage("저장된 비밀번호가 두 제출 중 정확히 하나와 일치해야 한다")
                .isTrue();
    }

    @Test
    @DisplayName("토큰이 없는 서로 다른 유저들이 동시에 재설정을 요청해도 교착 없이 각자 링크 하나를 받는다")
    void differentUsersRequestAtOnceDoNotDeadlock() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            List<User> users = new ArrayList<>();
            List<Runnable> actions = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                User user = newUser();
                users.add(user);
                String ip = "10.0." + (10 + round) + "." + i;
                actions.add(() -> passwordResetService.requestReset(user.getEmail(), ip));
            }

            List<Throwable> failures = runConcurrently(actions);

            assertThat(failures)
                    .withFailMessage("라운드 %d: 서로 다른 유저의 동시 요청에서 예외가 났다: %s", round, failures)
                    .isEmpty();
            for (User user : users) {
                assertThat(validTokenCount(user.getId()))
                        .withFailMessage("라운드 %d: 유저 %d의 유효한 토큰이 1개가 아니다", round, user.getId())
                        .isEqualTo(1);
            }
        }
    }
}
