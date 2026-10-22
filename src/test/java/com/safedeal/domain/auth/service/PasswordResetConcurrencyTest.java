package com.safedeal.domain.auth.service;

import com.safedeal.domain.auth.repository.PasswordResetTokenRepository;
import com.safedeal.domain.user.entity.User;
import com.safedeal.domain.user.repository.UserRepository;
import com.safedeal.testsupport.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
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
 * 재설정 요청 연타 — 같은 유저가 비밀번호 재설정을 동시에 여러 번 요청하는 상황을 재현한다
 * (CodeRabbit 지적, PR #40). D-15가 보장하려는 "최신 링크 1개"가 동시 요청에서도 유지되는지
 * 확인한다 — 유저 행 잠금(UserRepository.findForPasswordResetUpdate) 없이는 두 요청이 각자
 * "소진할 옛 토큰이 없다"고 보고 토큰을 하나씩 만들어 보장이 깨질 수 있다.
 *
 * {@code @Transactional}을 붙이지 않는 이유는 FavoriteConcurrencyTest와 같다 — 별도
 * 스레드는 테스트 트랜잭션 밖이라 롤백 대상이 아니고, 롤백 안에 갇히면 경합이 재현되지 않는다.
 */
class PasswordResetConcurrencyTest extends IntegrationTestSupport {

    /** 레이트리밋(주소당 시간당 3회)에 걸리지 않게 그 안에서 동시성을 재현한다. */
    private static final int THREADS = 3;

    @Autowired PasswordResetService passwordResetService;
    @Autowired PasswordResetTokenRepository passwordResetTokenRepository;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired StringRedisTemplate redisTemplate;

    private String email;
    private Long userId;

    @BeforeEach
    void setUp() {
        email = "reset-race-" + System.nanoTime() + "@test.com";
        User user = userRepository.save(
                User.createLocal(email, passwordEncoder.encode("password123"), "동시요청" + System.nanoTime()));
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

    /** 모든 스레드를 같은 순간에 풀어 경합 창을 넓힌다. 실패는 삼키지 않고 그대로 올린다. */
    private List<Throwable> runConcurrently(Runnable action) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Callable<Throwable>> tasks = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
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
        List<Throwable> failures =
                runConcurrently(() -> passwordResetService.requestReset(email, "127.0.0.1"));

        assertThat(failures)
                .withFailMessage("동시 재설정 요청에서 예외가 났다: %s", failures)
                .isEmpty();

        long validCount = passwordResetTokenRepository.findAll().stream()
                .filter(token -> token.getUserId().equals(userId))
                .filter(token -> token.getUsedAt() == null)
                .count();
        assertThat(validCount)
                .withFailMessage("유효한 토큰이 1개가 아니라 %d개 남았다 - '최신 링크 1개' 보장이 깨졌다", validCount)
                .isEqualTo(1);
    }
}
