package com.safedeal.domain.report.entity;

import com.safedeal.domain.report.exception.ReportErrorCode;
import com.safedeal.global.entity.MutableEntity;
import com.safedeal.global.exception.BusinessException;
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

import java.time.Instant;

/**
 * 신고 한 건. 상태가 바뀌므로(접수 → 검토 → 처리/기각, 취소) {@link MutableEntity}를 상속한다.
 *
 * <p>상태는 엔티티 setter로 바꾸지 않는다. {@code ReportRepository}의 조건부 UPDATE로만 바꾼다
 * (WHERE status = 기대값). 그래야 두 관리자가 동시에 처리해도 한쪽만 성공한다.
 *
 * <p>대상(매물·채팅방)의 내부 id와 공개 id를 함께 둔다. 공개 id는 목록·알림 링크에 쓰고,
 * 내부 id는 조건부 UPDATE와 UNIQUE 판정에 쓴다. 피신고자 id는 대상 종류에 따라 정해진 값이라
 * 저장 시점에 확정해 둔다({@code accusedUserId}).
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "reports",
        uniqueConstraints = {
                @UniqueConstraint(name = Report.UK_PUBLIC_ID, columnNames = "public_id"),
                // 중복 신고 방지(정책: 같은 사람 → 같은 대상 → 같은 사유는 1회).
                // 선두를 reporter_id로 둬서 내 신고 목록(RPT-2)이 이 인덱스를 그대로 탄다.
                @UniqueConstraint(name = Report.UK_REPORTER_TARGET_REASON,
                        columnNames = {"reporter_id", "target_type", "target_id", "reason_code"})
        },
        indexes = {
                // 관리자 큐(ADM-1): 상태별로 접수 시각 순.
                @Index(name = "idx_reports_queue", columnList = "status, received_at"),
                // 같은 대상의 열린 신고를 한꺼번에 종결할 때(일괄 종결) 범위를 좁힌다.
                @Index(name = "idx_reports_target", columnList = "target_type, target_id, status")
        }
)
public class Report extends MutableEntity {

    /** 제약 이름을 상수로 둔다 — 서비스가 "이 제약 위반일 때만" 판정할 때 같은 문자열을 쓴다. */
    public static final String UK_PUBLIC_ID = "uk_reports_public_id";
    public static final String UK_REPORTER_TARGET_REASON = "uk_reports_reporter_target_reason";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 외부 노출 식별자(ULID). 응답의 reportId가 이 값이다. */
    @Column(name = "public_id", nullable = false, length = 26, updatable = false)
    private String publicId;

    @Column(name = "reporter_id", nullable = false, updatable = false)
    private Long reporterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20, updatable = false)
    private ReportTargetType targetType;

    /** 대상의 내부 id(listings.id 또는 chat_rooms.id). */
    @Column(name = "target_id", nullable = false, updatable = false)
    private Long targetId;

    /** 대상의 공개 id. 목록·알림 링크에 join 없이 쓰려고 비정규화한다. */
    @Column(name = "target_public_id", nullable = false, length = 26, updatable = false)
    private String targetPublicId;

    /** 피신고자. 매물이면 판매자, 채팅이면 방의 신고자가 아닌 쪽. */
    @Column(name = "accused_user_id", nullable = false, updatable = false)
    private Long accusedUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 30, updatable = false)
    private ReportReason reasonCode;

    /** 상세 설명. 재신고(부활) 시 바뀔 수 있다. */
    @Column(length = 1000)
    private String detail;

    /** 채팅 신고의 증거 메시지 id. 매물 신고에는 없다. 재신고(부활) 시 바뀔 수 있다. */
    @Column(name = "evidence_message_id")
    private Long evidenceMessageId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReportStatus status;

    /** 큐 정렬 기준. 재신고로 되살리면 갱신한다. created_at은 최초 시각으로 그대로 둔다. */
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    /** 관리자 처리 기록. 종결 시 한 번 채운다. */
    @Column(name = "handled_by")
    private Long handledBy;

    @Column(name = "handled_at")
    private Instant handledAt;

    @Column(name = "admin_memo", length = 1000)
    private String adminMemo;

    private Report(String publicId, Long reporterId, ReportTargetType targetType, Long targetId,
                   String targetPublicId, Long accusedUserId, ReportReason reasonCode,
                   String detail, Long evidenceMessageId, Instant now) {
        this.publicId = publicId;
        this.reporterId = reporterId;
        this.targetType = targetType;
        this.targetId = targetId;
        this.targetPublicId = targetPublicId;
        this.accusedUserId = accusedUserId;
        this.reasonCode = reasonCode;
        this.detail = detail;
        this.evidenceMessageId = evidenceMessageId;
        this.status = ReportStatus.RECEIVED;
        this.receivedAt = now;
    }

    /**
     * 신고를 접수 상태로 만든다. 사유×대상 매트릭스가 여기서도 한 번 더 막히므로,
     * 서비스가 검증을 빠뜨려도 허용되지 않는 조합은 행으로 저장되지 않는다.
     */
    public static Report receive(String publicId, Long reporterId, ReportTargetType targetType,
                                 Long targetId, String targetPublicId, Long accusedUserId,
                                 ReportReason reasonCode, String detail, Long evidenceMessageId,
                                 Instant now) {
        if (!reasonCode.allowsFor(targetType)) {
            throw new BusinessException(ReportErrorCode.REASON_NOT_ALLOWED_FOR_TARGET);
        }
        return new Report(publicId, reporterId, targetType, targetId, targetPublicId,
                accusedUserId, reasonCode, detail, evidenceMessageId, now);
    }
}
