package com.safedeal.domain.auth.entity;

import com.safedeal.global.entity.MutableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 비밀번호 재설정 토큰 (정책 AUTH-7) — TTL 30분, 1회용. 이메일 인증 토큰(24시간)보다 짧게
 * 두는 이유: 이 링크 하나로 계정을 통째로 가져갈 수 있어 탈취 시 피해가 크다.
 *
 * 해시 저장·1회용 판정 이유는 {@link EmailVerificationToken}과 같다.
 */
@Entity
// markAllUsedByUserId(user_id 조건 UPDATE)가 전체를 훑으며 다른 유저 행까지 잠그지 않게 한다.
@Table(name = "password_reset_tokens",
        indexes = @Index(name = "idx_password_reset_tokens_user_id", columnList = "user_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PasswordResetToken extends MutableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false, unique = true, length = 64, updatable = false)
    private String tokenHash;

    @Column(nullable = false, updatable = false)
    private Instant expiresAt;

    private Instant usedAt;

    private PasswordResetToken(Long userId, String tokenHash, Instant expiresAt) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public static PasswordResetToken issue(Long userId, String tokenHash, Instant expiresAt) {
        return new PasswordResetToken(userId, tokenHash, expiresAt);
    }

    // 리뷰 봇 동작 확인용 임시 주석 — 확인 후 삭제한다.
    public boolean isUsable(Instant now) {
        return usedAt == null && now.isBefore(expiresAt);
    }

    public void markUsed(Instant now) {
        this.usedAt = now;
    }
}
