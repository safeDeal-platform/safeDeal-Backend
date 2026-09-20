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
 *
 * 삭제는 세 갈래다. {@link #revoke}(로그아웃·정리)만 예외를 삼킨다 — 로그아웃은 쓰기가
 * 실패해도 진행돼야 하고, 쿠키를 지우면 그 브라우저에서는 실제로 못 쓴다.
 * {@link #consume}(RTR 회전)과 {@link #revokeAll}(공격 대응)은 삼키지 않는다 — 전자는
 * 소진 여부가 곧 발급 여부를 정하는 판정이고, 후자는 지울 쿠키가 공격자 브라우저에 있어
 * 로그아웃 같은 대체 수단이 없다.
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

    /**
     * 이 jti를 화이트리스트에서 <b>원자적으로 걷어내고, 실제로 지운 쪽만 true</b>를 받는다.
     *
     * 재발급(RTR)의 심판이다. find로 확인한 뒤 revoke로 지우면 그 사이에 같은 refresh가 한 번
     * 더 들어왔을 때 둘 다 검사를 통과해, 토큰 하나에서 유효한 세션이 둘 나온다. HDEL은 지운
     * 필드 수를 돌려주므로 경쟁에서 이긴 요청이 하나로 확정된다.
     *
     * 실패는 삼키지 않는다(fail-closed) — 소진됐는지 모르는 채로 새 토큰을 발급할 수 없다.
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
     * 해당 유저의 모든 기기를 무효화한다 — <b>실패하면 예외를 올린다(fail-closed)</b>.
     *
     * 재사용 공격 감지(정책)와 비밀번호 재설정(AUTH-7) 두 곳에서 쓴다. 둘 다 "남이 이미 내
     * 계정에 들어와 있다"를 전제로 한 공격 대응이라, 무효화가 실패했는데 호출자에게 성공으로
     * 보이면 안 된다. 삼키면 사용자는 세션이 끊긴 줄 아는데 공격자 토큰은 그대로 살아 있고,
     * 그 사실이 경고 로그에만 남는다.
     *
     * 가용성 비용은 사실상 없다 — Redis가 죽으면 화이트리스트 저장도 fail-closed라 어차피
     * 로그인·재발급이 안 된다. 여기서만 통과시켜 봐야 얻는 게 없다.
     */
    public void revokeAll(Long userId) {
        redisTemplate.delete(key(userId));
    }

    private String key(Long userId) {
        return KEY_PREFIX + userId;
    }
}
