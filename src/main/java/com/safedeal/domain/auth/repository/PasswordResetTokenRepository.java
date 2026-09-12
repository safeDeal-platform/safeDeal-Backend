package com.safedeal.domain.auth.repository;

import com.safedeal.domain.auth.entity.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /**
     * 1회용 보장 — 조건부 UPDATE로 소진한다.
     * 사유는 {@link EmailVerificationTokenRepository#markUsed}와 같고, 이쪽은 링크 하나가
     * 계정을 통째로 넘길 수 있어 결과가 더 무겁다.
     */
    @Modifying(flushAutomatically = true)
    @Query("update PasswordResetToken t set t.usedAt = :now, t.updatedAt = :now"
            + " where t.id = :id and t.usedAt is null")
    int markUsed(@Param("id") Long id, @Param("now") Instant now);
}
