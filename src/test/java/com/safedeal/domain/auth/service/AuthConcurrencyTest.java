package com.safedeal.domain.auth.service;

import com.safedeal.domain.auth.dto.LoginRequest;
import com.safedeal.domain.auth.entity.EmailVerificationToken;
import com.safedeal.domain.auth.exception.AuthErrorCode;
import com.safedeal.domain.auth.repository.EmailVerificationTokenRepository;
import com.safedeal.domain.user.entity.User;
import com.safedeal.domain.user.repository.UserRepository;
import com.safedeal.global.exception.BusinessException;
import com.safedeal.global.security.JwtTokenProvider;
import com.safedeal.global.util.SecureToken;
import com.safedeal.testsupport.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 같은 1회용 값이 동시에 두 번 이상 들어올 때 — 토큰 재발급(AUTH-3)과 이메일 인증(AUTH-6).
 * 브라우저 탭 두 개가 동시에 재발급하거나, 인증 링크를 연달아 누르는 상황이다.
 *
 * {@code @Transactional}을 붙이지 않는 이유는 PasswordResetConcurrencyTest와 같다 — 별도
 * 스레드는 테스트 트랜잭션 밖이라 롤백 대상이 아니고, 롤백 안에 갇히면 경합이 재현되지 않는다.
 */
class AuthConcurrencyTest extends IntegrationTestSupport {

    private static final int THREADS = 3;

    /** 경합 타이밍은 확률적이라 라운드를 반복해 재현 확률을 높인다(보장이 아니라 회귀 방지용). */
    private static final int ROUNDS = 5;

    private static final String PASSWORD = "password123";

    @Autowired AuthCommandService authCommandService;
    @Autowired EmailVerificationService emailVerificationService;
    @Autowired EmailVerificationTokenRepository emailVerificationTokenRepository;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired StringRedisTemplate redisTemplate;

    private final List<Long> createdUserIds = new ArrayList<>();

    /** RefreshTokenStore.KEY_PREFIX(private)와 같은 값이다. 그쪽 버전을 올리면 여기도 같이 바꿔야 세션 수 단언이 헛돌지 않는다. */
    private static final String REFRESH_KEY_PREFIX = "auth:refresh:v1:";

    @AfterEach
    void clear() {
        for (Long userId : createdUserIds) {
            redisTemplate.delete(REFRESH_KEY_PREFIX + userId);
        }
        emailVerificationTokenRepository.deleteAllInBatch();
        userRepository.deleteAllByIdInBatch(createdUserIds);
        createdUserIds.clear();
    }

    private User newUser() {
        long n = System.nanoTime();
        User user = userRepository.save(
                User.createLocal("auth-race-" + n + "@test.com", passwordEncoder.encode(PASSWORD), "동시인증" + n));
        createdUserIds.add(user.getId());
        return user;
    }

    private static boolean hasCode(Throwable t, AuthErrorCode code) {
        return t instanceof BusinessException e && e.getErrorCode() == code;
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
    @DisplayName("같은 refresh로 동시에 재발급하면 한 요청만 새 토큰을 받고, 세션이 둘로 늘어나지 않는다")
    void concurrentReissueIssuesOnce() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            User user = newUser();
            String refresh = authCommandService.login(new LoginRequest(user.getEmail(), PASSWORD), "10.0.2." + round)
                    .refresh().value();

            List<Throwable> failures = runConcurrently(
                    Collections.nCopies(THREADS, () -> authCommandService.reissue(refresh)));

            assertThat(failures)
                    .withFailMessage("라운드 %d: 정확히 한 요청만 성공하고 나머지는 AUTH004여야 한다: %s", round, failures)
                    .hasSize(THREADS - 1)
                    .allMatch(t -> hasCode(t, AuthErrorCode.INVALID_TOKEN));

            // 늦게 온 요청이 '이미 쓰인 토큰'으로 판정돼 모든 기기를 끊으면 0개가 될 수 있다(AuthCommandService.reissue).
            // 2개 이상이면 같은 refresh로 세션이 복제된 것이다 — 그것만은 절대 안 된다.
            Long sessions = redisTemplate.opsForHash().size(REFRESH_KEY_PREFIX + user.getId());
            assertThat(sessions)
                    .withFailMessage("라운드 %d: 화이트리스트에 세션이 %d개 남았다 - 재발급이 중복됐다", round, sessions)
                    .isLessThanOrEqualTo(1L);
        }
    }

    @Test
    @DisplayName("같은 인증 링크를 동시에 여러 번 눌러도 한 번만 성공하고 계정은 인증 상태가 된다")
    void concurrentVerifySucceedsOnce() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            User user = newUser();
            String raw = SecureToken.generate();
            emailVerificationTokenRepository.save(EmailVerificationToken.issue(
                    user.getId(), JwtTokenProvider.hash(raw), Instant.now().plus(Duration.ofHours(1))));

            List<Throwable> failures = runConcurrently(
                    Collections.nCopies(THREADS, () -> emailVerificationService.verify(raw)));

            assertThat(failures)
                    .withFailMessage("라운드 %d: 정확히 한 요청만 성공하고 나머지는 AUTH008이어야 한다: %s", round, failures)
                    .hasSize(THREADS - 1)
                    .allMatch(t -> hasCode(t, AuthErrorCode.INVALID_EMAIL_VERIFICATION_TOKEN));
            assertThat(userRepository.findById(user.getId()).orElseThrow().isEmailVerified())
                    .withFailMessage("라운드 %d: 한 요청이 성공했는데 계정이 인증 상태가 아니다", round)
                    .isTrue();
        }
    }
}
