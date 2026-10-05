package com.safedeal.domain.auth.repository;

import com.safedeal.domain.auth.entity.EmailVerificationToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, Long> {

    /** 링크로 들어온 원문을 해시로 바꿔 조회한다 — 원문은 저장하지 않는다. */
    Optional<EmailVerificationToken> findByTokenHash(String tokenHash);

    /**
     * 1회용 보장 — 조건부 UPDATE로 소진한다(WHERE used_at IS NULL). 같은 링크를 동시에 두 번
     * 눌러도 DB가 한쪽만 성공시켜, 영향받은 행이 1일 때만 그 요청이 이긴 것이다.
     *
     * updated_at을 직접 채우는 이유: 벌크 UPDATE는 영속성 컨텍스트를 거치지 않아 자동 갱신 필드가 안 바뀐다.
     */
    @Modifying(flushAutomatically = true)
    @Query("update EmailVerificationToken t set t.usedAt = :now, t.updatedAt = :now"
            + " where t.id = :id and t.usedAt is null")
    int markUsed(@Param("id") Long id, @Param("now") Instant now);
}
