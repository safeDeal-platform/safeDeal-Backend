package com.safedeal.global.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * JWT 발급·검증. domain/auth가 아니라 global에 두는 이유는, 발급·검증이 도메인 지식 없는
 * 기계적 작업이기 때문이다 — 반대로 두면 global→domain 역방향 의존이 생긴다.
 *
 * access·refresh 모두 JWT로 발급하되 typ 클레임으로 구분한다 — 구분이 없으면 refresh를
 * Authorization 헤더에 넣어 3일짜리 access처럼 쓸 수 있다.
 *
 * refresh를 불투명 문자열이 아니라 JWT로 하는 이유: userId를 알아야 화이트리스트 키를
 * 찾을 수 있고, 그래야 재사용 감지 시 해당 유저 refresh를 전부 무효화할 수 있다.
 */
@Slf4j
@Component
public class JwtTokenProvider {

    private static final String CLAIM_TYPE = "typ";
    private static final String CLAIM_ROLE = "role";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    // HS256의 키 하한. jjwt도 같은 값으로 검사하지만, 여기서 먼저 걸러야 "왜 기동이 실패하는지"가
    // 설정 문제로 명확히 드러난다.
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final Duration accessTtl;
    private final Duration refreshTtl;

    public JwtTokenProvider(JwtProperties properties) {
        byte[] secret = properties.getSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "app.jwt.secret이 너무 짧습니다 - HS256은 최소 " + MIN_SECRET_BYTES + "바이트가 필요합니다.");
        }
        this.key = Keys.hmacShaKeyFor(secret);
        this.accessTtl = properties.getAccessTtl();
        this.refreshTtl = properties.getRefreshTtl();
    }

    /** 발급된 토큰과 그 부산물. jti·만료 시각은 화이트리스트 저장과 응답 구성에 필요해서 함께 돌려준다. */
    public record IssuedToken(String value, String jti, Instant expiresAt) {
    }

    /** access 토큰이 담고 있는, 인증에 필요한 정보. */
    public record AccessClaims(Long userId, String role, String jti, Instant expiresAt) {
    }

    /** refresh 토큰이 담고 있는, 화이트리스트 조회에 필요한 정보. */
    public record RefreshClaims(Long userId, String jti, Instant expiresAt) {
    }

    public IssuedToken issueAccess(Long userId, String role) {
        return issue(userId, TYPE_ACCESS, role, accessTtl);
    }

    public IssuedToken issueRefresh(Long userId) {
        return issue(userId, TYPE_REFRESH, null, refreshTtl);
    }

    private IssuedToken issue(Long userId, String type, String role, Duration ttl) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttl);
        String jti = UUID.randomUUID().toString();

        var builder = Jwts.builder()
                .subject(String.valueOf(userId))
                .id(jti)
                .claim(CLAIM_TYPE, type)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key);
        if (role != null) {
            builder.claim(CLAIM_ROLE, role);
        }

        return new IssuedToken(builder.compact(), jti, expiresAt);
    }

    /**
     * access 토큰을 해석한다. 서명·만료·타입 중 하나라도 어긋나면 빈 값을 준다.
     * 예외 대신 Optional인 이유: 호출자가 할 일은 "인증 않고 통과"뿐이라 try-catch가 필요 없다.
     */
    public Optional<AccessClaims> resolveAccess(String token) {
        return parse(token, TYPE_ACCESS)
                .map(claims -> new AccessClaims(
                        Long.valueOf(claims.getSubject()),
                        claims.get(CLAIM_ROLE, String.class),
                        claims.getId(),
                        claims.getExpiration().toInstant()));
    }

    public Optional<RefreshClaims> resolveRefresh(String token) {
        return parse(token, TYPE_REFRESH)
                .map(claims -> new RefreshClaims(
                        Long.valueOf(claims.getSubject()),
                        claims.getId(),
                        claims.getExpiration().toInstant()));
    }

    private Optional<Claims> parse(String token, String expectedType) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            if (!expectedType.equals(claims.get(CLAIM_TYPE, String.class))) {
                return Optional.empty();
            }
            return Optional.of(claims);
        } catch (JwtException | IllegalArgumentException e) {
            // 토큰 문자열은 로그에 남기지 않는다 - 유효한 토큰이 로그 수집기로 흘러가면
            // 로그를 읽을 수 있는 사람이 그대로 남의 세션을 쓸 수 있다.
            log.debug("JWT 해석 실패 - reason={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * 화이트리스트·1회용 토큰 테이블에 저장할 해시(SHA-256 hex) — 원문을 저장하면 Redis 덤프나
     * DB 조회만으로 남의 refresh·재설정 링크를 그대로 쓸 수 있게 된다.
     */
    public static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 모든 JVM이 반드시 제공한다(스펙 필수). 여기 오면 런타임이 깨진 것이다.
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }
}
