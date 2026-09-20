package com.safedeal.global.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 운영 필수 설정 가드의 회귀 테스트.
 *
 * 배경: 해석되지 않은 플레이스홀더는 예외가 아니라 문자열 그대로 바인딩되기 때문에,
 * REDIS_HOST를 빼먹고 prod로 배포해도 앱이 정상 기동해버리는 것을 실측으로 확인했다.
 * 이 가드가 그 구멍을 막고 있으므로, 가드의 동작 자체를 테스트로 고정한다.
 */
class RequiredPropertyGuardTest {

    private MockEnvironment fullyConfigured() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.datasource.url", "jdbc:mysql://db:3306/safedeal");
        env.setProperty("spring.datasource.username", "app");
        env.setProperty("spring.datasource.password", "secret");
        env.setProperty("spring.data.redis.host", "redis.internal");
        env.setProperty("spring.kafka.bootstrap-servers", "kafka.internal:9092");
        env.setProperty("app.cors.allowed-origins", "https://safedeal.example.com");
        env.setProperty("app.kafka.consumer-group.chat-fanout", "safedeal-chat-fanout-task1");
        env.setProperty("app.jwt.secret", "test-secret-key-at-least-32-bytes-long!!");
        env.setProperty("app.mail.provider", "resend");
        return env;
    }

    @Test
    @DisplayName("필수값이 전부 있으면 통과한다")
    void allPresent_passes() {
        assertThatCode(() -> new RequiredPropertyGuard(fullyConfigured()).checkRequiredProperties())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("운영에서 메일 제공자가 log면 기동을 막는다 (값이 있는 것만으로는 부족하다)")
    void logMailProviderInProd_isRejected() {
        MockEnvironment env = fullyConfigured();
        env.setProperty("app.mail.provider", "log");

        assertThatThrownBy(() -> new RequiredPropertyGuard(env).checkRequiredProperties())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.mail.provider");
    }

    @Test
    @DisplayName("누락된 키를 전부 모아서 한 번에 보고한다")
    void missingKeys_areReportedTogether() {
        MockEnvironment env = fullyConfigured();
        env.setProperty("spring.data.redis.host", "");
        env.setProperty("app.kafka.consumer-group.chat-fanout", "");

        assertThatThrownBy(() -> new RequiredPropertyGuard(env).checkRequiredProperties())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.data.redis.host")
                .hasMessageContaining("app.kafka.consumer-group.chat-fanout");
    }

    @Test
    @DisplayName("해석되지 않은 플레이스홀더 문자열도 누락으로 판정한다")
    void unresolvedPlaceholder_isMissing() {
        MockEnvironment env = fullyConfigured();
        // MockEnvironment는 값 안의 플레이스홀더를 다시 해석하려다 실패해 예외를 던진다.
        // 가드는 그 예외를 누락으로 처리한다 — 실제 prod에서 환경변수가 빠졌을 때의 경로.
        env.setProperty("spring.data.redis.host", "${REDIS_HOST}");

        assertThatThrownBy(() -> new RequiredPropertyGuard(env).checkRequiredProperties())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.data.redis.host");
    }
}
