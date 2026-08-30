package com.safedeal.domain.auth.repository;

import com.safedeal.global.security.JwtProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 유효한 refresh 토큰 화이트리스트 (정책 '리프레시 토큰' — Redis가 판정 권위).
 *
 * <pre>
 * auth:refresh:v1:{userId}   HASH   field=jti   value=tokenHash|expiresAtEpochMilli
 *                                   키 TTL = refreshTtl + 1일 (안전망일 뿐, 만료 판정 아님)
 * </pre>
 *
 * <b>왜 유저별 Hash 하나인가</b>: 정책이 "재사용 감지 시 해당 유저 refresh 전부 무효화"를
 * 요구한다. 키를 {userId}:{jti}로 흩어놓으면 전체 삭제에 SCAN이 필요해 운영에서 느리고 위험한데,
 * Hash로 묶으면 DEL 한 번이면 끝난다.
 *
 * <b>왜 키 TTL이 아니라 value의 expiresAt으로 만료를 판정하는가</b>: Redis의 TTL은 키 단위이지
 * 필드 단위가 아니다. 한 유저의 모든 기기 토큰이 한 키를 공유하므로 TTL도 공유되고, 새 기기에서
 * 로그인할 때마다 EXPIRE가 갱신돼 3일 전에 발급된 토큰이 영영 안 죽는다("3일" 정책이 실제로는
 * 지켜지지 않는다). 필드 단위 TTL(HEXPIRE)은 Redis 7.4+ 기능인데 로컬 인프라가 7.2라 쓸 수 없다.
 * 그래서 value에 만료 시각을 같이 저장하고 검증할 때 직접 비교한다 — 어차피 검증마다 value를
 * 읽으므로 추가 비용이 없다. 키 TTL은 고아 키가 영구히 남지 않게 하는 안전망으로만 둔다.
 *
 * <b>실패 처리</b>: 저장·조회는 fail-closed다(예외를 그대로 올린다). 화이트리스트는 "있어야
 * 통과"하는 구조라 조회를 못 했을 때 통과시킬 방법이 원리적으로 없고, 저장에 실패했는데 로그인을
 * 성공시키면 화이트리스트에 없는 refresh가 발급돼 다음 재발급 때 재사용 공격으로 오판된다.
 * 삭제만 예외를 삼킨다 — 로그아웃은 쓰기가 실패해도 진행돼야 하기 때문이다.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class RefreshTokenStore {

    private static final String KEY_PREFIX = "auth:refresh:v1:";
    private static final String DELIMITER = "|";
    private static final Duration KEY_TTL_MARGIN = Duration.ofDays(1);

    private final StringRedisTemplate redisTemplate;
    private final JwtProperties jwtProperties;

    /** 화이트리스트에 저장된 한 기기의 refresh 정보. */
    public record Entry(String tokenHash, Instant expiresAt) {

        public boolean isExpired(Instant now) {
            return !now.isBefore(expiresAt);
        }
    }

    public void save(Long userId, String jti, String tokenHash, Instant expiresAt) {
        String key = key(userId);
        redisTemplate.opsForHash().put(key, jti, tokenHash + DELIMITER + expiresAt.toEpochMilli());
        redisTemplate.expire(key, jwtProperties.getRefreshTtl().plus(KEY_TTL_MARGIN));
    }

    public Optional<Entry> find(Long userId, String jti) {
        Object raw = redisTemplate.opsForHash().get(key(userId), jti);
        if (raw == null) {
            return Optional.empty();
        }
        // JSON을 쓰지 않는 이유: 값이 두 개뿐인데 직렬화기를 끼우면 Jackson 버전 전환(Boot 4는
        // Jackson 3)에 이 저장소가 끌려다닌다. 형식은 내부 전용이므로 단순 구분자로 충분하다.
        String[] parts = raw.toString().split("\\" + DELIMITER, 2);
        if (parts.length != 2) {
            log.warn("화이트리스트 값 형식이 올바르지 않아 무시한다 - userId={}", userId);
            return Optional.empty();
        }
        try {
            return Optional.of(new Entry(parts[0], Instant.ofEpochMilli(Long.parseLong(parts[1]))));
        } catch (NumberFormatException e) {
            log.warn("화이트리스트 만료 시각을 읽을 수 없어 무시한다 - userId={}", userId);
            return Optional.empty();
        }
    }

    /** 재발급(RTR) 시 직전 토큰을 즉시 폐기하거나, 만료된 필드를 정리할 때 쓴다. */
    public void revoke(Long userId, String jti) {
        try {
            redisTemplate.opsForHash().delete(key(userId), jti);
        } catch (RuntimeException e) {
            log.warn("refresh 폐기 실패 - reason={}", e.getClass().getSimpleName());
        }
    }

    /**
     * 해당 유저의 모든 기기를 무효화한다.
     * 재사용 공격 감지(정책)와 비밀번호 재설정(AUTH-7) 두 곳에서 쓴다.
     */
    public void revokeAll(Long userId) {
        try {
            redisTemplate.delete(key(userId));
        } catch (RuntimeException e) {
            log.warn("refresh 전체 무효화 실패 - reason={}", e.getClass().getSimpleName());
        }
    }

    private String key(Long userId) {
        return KEY_PREFIX + userId;
    }
}
