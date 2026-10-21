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
     * 1회용 보장 — 조건부 UPDATE로 소진한다. 사유는 {@link EmailVerificationTokenRepository#markUsed}와
     * 같다 — 다만 이쪽은 링크 하나가 계정을 통째로 넘길 수 있어 결과가 더 무겁다.
     */
    @Modifying(flushAutomatically = true)
    @Query("update PasswordResetToken t set t.usedAt = :now, t.updatedAt = :now"
            + " where t.id = :id and t.usedAt is null")
    int markUsed(@Param("id") Long id, @Param("now") Instant now);

    /**
     * 같은 유저의 미사용 재설정 토큰을 전부 소진한다(D-15 — 새 링크 발급 시 이전 링크 무효화).
     * 0행이어도 정상이다(처음 요청하는 유저는 소진할 게 없다). 새 토큰을 저장하기 전에만 불러야
     * 한다 — 뒤에 부르면 방금 만든 토큰도 usedAt IS NULL이라 같이 죽는다.
     */
    @Modifying(flushAutomatically = true)
    @Query("update PasswordResetToken t set t.usedAt = :now, t.updatedAt = :now"
            + " where t.userId = :userId and t.usedAt is null")
    int markAllUsedByUserId(@Param("userId") Long userId, @Param("now") Instant now);
}
