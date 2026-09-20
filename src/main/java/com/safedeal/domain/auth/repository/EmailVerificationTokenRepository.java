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
     * 1회용 보장 — 조건부 UPDATE로 소진한다(공통 원칙 '상태 전이는 조건부 UPDATE').
     *
     * 조회해서 usedAt이 null인지 보고 setter로 채우면, 같은 링크를 동시에 두 번 눌렀을 때
     * 두 요청이 모두 "아직 안 썼다"를 읽고 둘 다 통과한다. 서버가 두 대면 더 쉽게 겹친다.
     * WHERE used_at IS NULL을 걸면 DB가 한 쪽만 성공시키므로, 영향 행이 1일 때만 이긴 것이다.
     *
     * updated_at을 직접 채우는 이유: JPQL 벌크 UPDATE는 영속성 컨텍스트를 거치지 않아
     * {@code @LastModifiedDate} 감사 필드가 갱신되지 않는다.
     */
    @Modifying(flushAutomatically = true)
    @Query("update EmailVerificationToken t set t.usedAt = :now, t.updatedAt = :now"
            + " where t.id = :id and t.usedAt is null")
    int markUsed(@Param("id") Long id, @Param("now") Instant now);
}
