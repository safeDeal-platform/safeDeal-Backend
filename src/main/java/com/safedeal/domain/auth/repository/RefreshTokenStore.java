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
 * 유저별로 Hash 하나를 쓰는 이유: 재사용 감지 시 그 유저의 refresh를 전부 지워야 하는데,
 * 키를 흩어놓으면 전체 삭제에 SCAN이 필요해 느리고 위험하다. Hash면 DEL 한 번으로 끝난다.
 *
 * 만료는 키 TTL이 아니라 저장된 값의 expiresAt으로 직접 비교해 판정한다 — 한 유저의 모든
 * 기기가 같은 키(TTL)를 공유해서, 키 TTL만 믿으면 새 기기 로그인마다 TTL이 갱신되어 오래된
 * 토큰이 영영 안 죽는다. 키 TTL은 고아 키 정리용 안전망일 뿐이다.
 *
 * 저장·조회는 실패하면 예외를 그대로 올린다(fail-closed) — 화이트리스트는 "있어야 통과"하는
 * 구조라, 저장 실패를 삼키고 로그인을 성공시키면 다음 재발급 때 정상 토큰이 재사용 공격으로 오판된다.
 *
 * 삭제 중 {@link #revoke}만 예외를 삼킨다(로그아웃은 실패해도 진행돼야 한다). {@link #consume}과
 * {@link #revokeAll}은 삼키지 않는다 — 전자는 소진 여부가 곧 발급 판정이고, 후자는 실패 시
 * 공격자 세션이 그대로 살아남는다.
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
        // JSON을 안 쓰는 이유: 값이 두 개뿐이라 직렬화기 없이 구분자로 충분하고, 형식은 내부 전용이다.
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

    /**
     * jti를 화이트리스트에서 원자적으로 지우고, 실제로 지운 요청만 true를 받는다(재발급의 심판).
     * 확인 후 따로 지우면 같은 refresh가 동시에 두 번 들어왔을 때 둘 다 통과해 세션이 두 개로
     * 늘어난다 — 지운 개수를 돌려주는 연산 하나로 경쟁을 원자적으로 가른다. 실패 시 예외를 그대로 올린다.
     */
    public boolean consume(Long userId, String jti) {
        Long removed = redisTemplate.opsForHash().delete(key(userId), jti);
        return removed != null && removed == 1L;
    }

    /** 만료·해시 불일치로 걸러낸 필드를 정리하거나, 로그아웃에서 쓴다(실패해도 진행). */
    public void revoke(Long userId, String jti) {
        try {
            redisTemplate.opsForHash().delete(key(userId), jti);
        } catch (RuntimeException e) {
            log.warn("refresh 폐기 실패 - reason={}", e.getClass().getSimpleName());
        }
    }

    /**
     * 해당 유저의 모든 기기를 무효화한다 — 실패하면 예외를 올린다(fail-closed). 재사용 공격 감지와
     * 비밀번호 재설정(AUTH-7)에서 쓰는데, 둘 다 "이미 침입당했다"는 전제라 무효화 실패를 성공으로
     * 보이면 공격자 토큰이 살아있는 채로 사용자만 안심하게 된다.
     */
    public void revokeAll(Long userId) {
        redisTemplate.delete(key(userId));
    }

    private String key(Long userId) {
        return KEY_PREFIX + userId;
    }
}
