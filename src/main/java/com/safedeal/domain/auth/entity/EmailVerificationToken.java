package com.safedeal.domain.auth.entity;

import com.safedeal.global.entity.MutableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 이메일 인증 토큰 (정책 AUTH-6) — TTL 24시간, 1회용.
 *
 * 원문이 아니라 해시를 저장한다. DB를 읽을 수 있는 사람이 남의 인증 링크를 그대로 쓸 수 있으면
 * 안 되기 때문이다 — 값을 아는 쪽(메일을 받은 본인)만 대조를 통과한다.
 *
 * {@link MutableEntity}를 상속하는 이유: used_at이 나중에 채워지는 UPDATE가 실제로 존재한다.
 * 감사 필드 규칙이 "테이블 이름이 아니라 실제 UPDATE 존재 여부로 판정"하라고 하므로 여기서는
 * updated_at을 갖는 쪽이 맞다.
 * (ERD에는 이 테이블에 updated_at이 없다 — ERD 쪽을 맞춰야 한다.)
 */
@Entity
@Table(name = "email_verification_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EmailVerificationToken extends MutableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long userId;

    /** SHA-256 hex는 항상 64자다. */
    @Column(nullable = false, unique = true, length = 64, updatable = false)
    private String tokenHash;

    @Column(nullable = false, updatable = false)
    private Instant expiresAt;

    /** null이면 아직 안 쓴 토큰. 1회용 보장은 이 값으로 한다. */
    private Instant usedAt;

    private EmailVerificationToken(Long userId, String tokenHash, Instant expiresAt) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public static EmailVerificationToken issue(Long userId, String tokenHash, Instant expiresAt) {
        return new EmailVerificationToken(userId, tokenHash, expiresAt);
    }

    public boolean isUsable(Instant now) {
        return usedAt == null && now.isBefore(expiresAt);
    }

    public void markUsed(Instant now) {
        this.usedAt = now;
    }
}
