package com.safedeal.domain.trust.entity;

import com.safedeal.global.entity.CreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 신뢰도 점수 변동 이력 (정책 TRS-4). {@link CreatedEntity}만 쓰는 이유(append-only): 점수를
 * 되돌릴 일이 생겨도 UPDATE가 아니라 반대 방향 행을 새로 넣는다 — 그래야 행의 나열만으로
 * "왜 지금 이 점수인가"를 재현할 수 있다.
 *
 * <b>멱등 키가 둘인 이유</b>(at-least-once 전달이라 같은 사실이 두 번 올 수 있다):
 * <ul>
 *   <li>{@code event_id} UNIQUE — 같은 이벤트의 재전달을 막는다</li>
 *   <li>{@code (user_id, reason_code, ref_type, ref_id)} UNIQUE — 발행 측이 eventId를 새로
 *       만들어 재시도해도 같은 <b>사실</b>이면 막는다(요구사항 TRS-4)</li>
 * </ul>
 * 하나만 두면 발행 측 구현에 기대야 하는 경우가 생겨 조용히 중복 반영될 수 있다 — 그래서 둘
 * 다 둔다. user_id 단독 인덱스는 없다 — 위 UNIQUE의 선두 컬럼이 user_id라 그 인덱스를 그대로 탄다.
 */
@Entity
@Table(
        name = "trust_score_logs",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_trust_log_event", columnNames = "event_id"),
                @UniqueConstraint(
                        name = "uk_trust_log_fact",
                        columnNames = {"user_id", "reason_code", "ref_type", "ref_id"})
        },
        // 어뷰징 판정(동일 상대 월 3회)이 매 거래완료마다 도는 조회다 — 유저·상대·사유로 좁힌다.
        indexes = @Index(
                name = "idx_trust_log_abuse",
                columnList = "user_id, counterparty_id, reason_code, created_at")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TrustScoreLog extends CreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 점수가 움직인 대상. 후기라면 reviewee_id, 거래완료라면 양쪽 각각 한 행씩이다. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 30, updatable = false)
    private TrustReasonCode reasonCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "ref_type", nullable = false, length = 20, updatable = false)
    private TrustRefType refType;

    @Column(name = "ref_id", nullable = false, updatable = false)
    private Long refId;

    /**
     * 실제로 반영된 증감폭. {@link TrustReasonCode#delta()}와 다를 수 있다 — 어뷰징 한도나
     * 하한·상한에 막혀 일부만 반영된 경우다.
     */
    @Column(nullable = false, updatable = false)
    private int delta;

    /** 반영 후 점수. 이력만 보고 점수 변화를 재현할 수 있게 남긴다(정책 TRS-4). */
    @Column(name = "score_after", nullable = false, updatable = false)
    private int scoreAfter;

    /** 거래 상대 (정책 TRS-5의 어뷰징 판정 축). 거래완료에만 있고 감점·후기는 null이다. */
    @Column(name = "counterparty_id", updatable = false)
    private Long counterpartyId;

    @Column(name = "event_id", nullable = false, length = 100, updatable = false)
    private String eventId;

    private TrustScoreLog(Long userId, TrustReasonCode reasonCode, TrustRefType refType, Long refId,
                          int delta, int scoreAfter, Long counterpartyId, String eventId) {
        this.userId = userId;
        this.reasonCode = reasonCode;
        this.refType = refType;
        this.refId = refId;
        this.delta = delta;
        this.scoreAfter = scoreAfter;
        this.counterpartyId = counterpartyId;
        this.eventId = eventId;
    }

    public static TrustScoreLog record(Long userId, TrustReasonCode reasonCode, TrustRefType refType,
                                       Long refId, int delta, int scoreAfter, Long counterpartyId,
                                       String eventId) {
        return new TrustScoreLog(userId, reasonCode, refType, refId, delta, scoreAfter,
                counterpartyId, eventId);
    }
}
