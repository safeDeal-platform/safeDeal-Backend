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
 * 신뢰도 점수 변동 이력 (정책 TRS-4).
 *
 * {@link CreatedEntity}를 상속하는 이유: 이 표는 append-only 원장이고 행을 나중에 고치지
 * 않는다. 감사 필드 규칙이 "실제 UPDATE가 있는 테이블만 updated_at"이라 여기엔 없는 쪽이 맞다.
 * 점수를 되돌릴 일이 생겨도 UPDATE가 아니라 반대 방향 행을 새로 넣는다 — 그래야 "왜 지금
 * 이 점수인가"를 행의 나열만으로 재현할 수 있다.
 *
 * <b>멱등 키가 둘인 이유</b>: at-least-once 전달이라 같은 사실이 두 번 올 수 있다.
 * <ul>
 *   <li>{@code event_id} UNIQUE — 같은 이벤트의 재전달을 막는다(정책 '감점 이벤트' 명시)</li>
 *   <li>{@code (user_id, reason_code, ref_type, ref_id)} UNIQUE — 발행 측이 재시도하며
 *       eventId를 새로 만들어 보내도 같은 <b>사실</b>이면 막는다(요구사항 TRS-4)</li>
 * </ul>
 * 둘 중 하나만으로는 부족하다. 앞의 것은 발행 측이 eventId를 안정적으로 재사용해야만 동작하고,
 * 뒤의 것은 "같은 사실을 두 번 세지 않는다"는 업무 규칙 자체다. RPT-6은 eventId를
 * 대상타입+대상ID로 만들라고 해서 둘이 사실상 같아지지만, 그건 신고 도메인의 약속이고
 * 수신 측이 그 약속에만 기대면 약속이 깨졌을 때 조용히 중복 반영된다.
 *
 * user_id 단독 인덱스를 따로 두지 않는 이유: 아래 UNIQUE 제약의 선두 컬럼이 user_id라
 * "이 유저의 이력" 조회는 그 인덱스를 그대로 탄다.
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
     * 실제로 반영된 증감폭.
     *
     * {@link TrustReasonCode#delta()}와 다를 수 있다 — 어뷰징 한도에 걸려 0으로 깎였거나,
     * 하한·상한에 막혀 일부만 반영된 경우다. 매핑표는 나중에 바뀌므로, "그때 얼마가
     * 반영됐는가"는 이 컬럼에만 남는다.
     */
    @Column(nullable = false, updatable = false)
    private int delta;

    /** 반영 후 점수. 이력만 보고 점수 변화를 재현할 수 있게 남긴다(정책 TRS-4). */
    @Column(name = "score_after", nullable = false, updatable = false)
    private int scoreAfter;

    /**
     * 거래 상대 (정책 TRS-5의 어뷰징 판정 축).
     *
     * 거래완료에만 있다. 감점·후기는 상대가 없거나 판정에 쓰지 않아 null이다.
     */
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
