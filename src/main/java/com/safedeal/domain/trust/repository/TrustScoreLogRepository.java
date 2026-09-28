package com.safedeal.domain.trust.repository;

import com.safedeal.domain.trust.entity.TrustReasonCode;
import com.safedeal.domain.trust.entity.TrustRefType;
import com.safedeal.domain.trust.entity.TrustScoreLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

/**
 * 조건이 고정된 조회만 있어 JPA Repository로 충분하다(동적 조건은 QueryDSL — 데이터 접근 전략 참고).
 */
public interface TrustScoreLogRepository extends JpaRepository<TrustScoreLog, Long> {

    /** 같은 이벤트의 재전달인지. at-least-once 전달이라 항상 먼저 확인한다. */
    boolean existsByEventId(String eventId);

    /**
     * 같은 <b>사실</b>이 이미 반영됐는지. 발행 측이 eventId를 새로 만들어 재시도하는 경우를
     * 잡는다 — eventId만 보면 그때 중복 반영된다.
     */
    boolean existsByUserIdAndReasonCodeAndRefTypeAndRefId(
            Long userId, TrustReasonCode reasonCode, TrustRefType refType, Long refId);

    /**
     * 이번 달에 같은 상대로부터 실제로 <b>가산된</b> 횟수 (정책 TRS-5, 동일 상대 월 3회).
     * {@code delta <> 0}으로 거르는 이유: 한도 초과로 0점 기록된 행까지 세면 "가산 횟수"가
     * 아니라 "이벤트 수"가 되어 나중에 한도를 조정할 때 잘못 읽힌다.
     */
    @Query("""
            select count(l) from TrustScoreLog l
             where l.userId = :userId
               and l.counterpartyId = :counterpartyId
               and l.reasonCode = :reasonCode
               and l.delta <> 0
               and l.createdAt >= :since
            """)
    long countGrantedSince(@Param("userId") Long userId,
                           @Param("counterpartyId") Long counterpartyId,
                           @Param("reasonCode") TrustReasonCode reasonCode,
                           @Param("since") Instant since);
}
