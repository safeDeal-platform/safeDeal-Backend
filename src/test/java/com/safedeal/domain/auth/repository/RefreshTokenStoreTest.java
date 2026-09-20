package com.safedeal.domain.auth.repository;

import com.safedeal.global.security.JwtProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 화이트리스트 삭제의 실패 처리를 고정한다.
 *
 * 로그아웃·RTR 회전(best-effort)과 공격 대응(fail-closed)의 구분이 이 클래스의 보안 가정이다.
 * 전체 무효화가 조용히 실패하면 사용자는 세션이 끊긴 줄 알지만 공격자 토큰은 살아 있다.
 * Redis 장애를 일으켜야 해서 Testcontainers 대신 mock으로 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenStoreTest {

    @Mock
    StringRedisTemplate redisTemplate;

    @Mock
    JwtProperties jwtProperties;

    @Mock
    HashOperations<String, Object, Object> hashOperations;

    private RefreshTokenStore store() {
        return new RefreshTokenStore(redisTemplate, jwtProperties);
    }

    @Test
    @DisplayName("전체 무효화(재사용 감지·비밀번호 재설정)는 Redis 장애를 삼키지 않는다")
    void revokeAllFailsClosed() {
        when(redisTemplate.delete(anyString()))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        assertThatThrownBy(() -> store().revokeAll(1L))
                .isInstanceOf(RedisConnectionFailureException.class);
    }

    @Test
    @DisplayName("단건 폐기(로그아웃·RTR 회전)는 Redis 장애를 삼키고 진행한다")
    void revokeIsBestEffort() {
        when(redisTemplate.<Object, Object>opsForHash()).thenReturn(hashOperations);
        when(hashOperations.delete(anyString(), any()))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        assertThatCode(() -> store().revoke(1L, "jti")).doesNotThrowAnyException();
    }
}
