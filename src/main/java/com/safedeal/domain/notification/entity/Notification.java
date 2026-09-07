package com.safedeal.domain.notification.entity;

import com.safedeal.global.entity.MutableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * 사용자에게 전달되는 알림 한 건.
 *
 * <p><b>발송은 1회성이지만 읽음 처리는 있다.</b> 정책(재민/알림 — '결정 이력')이 2026-08-30에
 * 뒤집혔다: 원래는 "1회성이라 읽음 처리 불필요"였으나 <b>확정(2026-08-30)으로 read_at이
 * 되살아났고</b>, 목록에서 읽은 알림과 안 읽은 알림을 구분하기로 했다. "1회성 발송"과 "읽음
 * 표시"는 별개 개념이다 — 발송은 여전히 한 번이고, 생성된 레코드에 읽은 시각만 기록된다.
 * 옛 주석("읽음 처리가 없어 read_at 컬럼을 두지 않는다")을 보고 되돌리지 말 것.
 *
 * <p>알림 레코드 자체가 IN_APP 전달의 진실(source of truth)이며, 즉시 push가 유실돼도 목록
 * 조회(커서 폴링)로 복구된다.
 *
 * <p><b>스키마 메모(targetId):</b> V1 초안(db/migration)은 target_id를 BIGINT(내부 PK)로
 * 두었으나, API 명세서(알림 목록 조회)의 응답 targetId는 공개 식별자(예: 매물 ULID)
 * 문자열이다. 알림 조회 경로가 다른 도메인(매물 등)을 join 없이 자기완결적으로 응답하도록,
 * 알림 생성 시점에 이미 알고 있는 <b>공개 참조 문자열</b>을 그대로 저장한다.
 * (MVP 동안 스키마 진실은 엔티티 — 이 결정은 V1 확정 시 팀과 함께 반영한다.)
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "notifications",
        indexes = {
                // 사용자별 최신순 조회(목록/폴링)의 주 인덱스. id DESC로 커서(sinceId) 증분에도 쓰인다.
                @Index(name = "idx_notifications_user_id", columnList = "user_id, id"),
                @Index(name = "idx_notifications_target", columnList = "target_type, target_id")
        }
)
public class Notification extends MutableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 수신자 users.id (내부 PK). */
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40, updatable = false)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private NotificationChannel channel;

    @Column(nullable = false, length = 200, updatable = false)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT", updatable = false)
    private String body;

    /** 딥링크 대상 종류. 이동 대상이 없으면 null. */
    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", length = 30, updatable = false)
    private NotificationTargetType targetType;

    /** 딥링크 대상의 공개 식별자(예: 매물 ULID). 대상이 없으면 null. */
    @Column(name = "target_id", length = 40, updatable = false)
    private String targetId;

    /** 채널별 부가 데이터(JSON). 목록 응답에는 포함하지 않는다. */
    @Column(columnDefinition = "json")
    private String metadata;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationStatus status;

    /** 전송 재시도 횟수(외부 채널용). */
    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    /**
     * 읽은 시각. null이면 아직 안 읽음 — 목록의 읽음/안읽음 구분이 이 값 하나로 결정된다.
     *
     * <p>별도 boolean 컬럼을 두지 않는 이유: 두 값을 함께 두면 "read=true인데 read_at이 null"
     * 같은 모순 상태가 생길 수 있고, 시각은 나중에 "언제 확인했나"가 필요해질 때 그대로 쓸 수
     * 있다. 정책도 boolean이 아니라 read_at 복구로 확정했다.
     */
    @Column(name = "read_at")
    private Instant readAt;

    private Notification(Long userId, NotificationType type, NotificationChannel channel,
                         String title, String body,
                         NotificationTargetType targetType, String targetId,
                         String metadata, NotificationStatus status) {
        this.userId = userId;
        this.type = type;
        this.channel = channel;
        this.title = title;
        this.body = body;
        this.targetType = targetType;
        this.targetId = targetId;
        this.metadata = metadata;
        this.status = status;
        this.retryCount = 0;
    }

    /**
     * IN_APP 알림 생성. 레코드가 곧 전달이므로 상태는 {@link NotificationStatus#SENT}로 시작한다.
     *
     * @param targetType 딥링크 대상 종류(없으면 null)
     * @param targetId   딥링크 대상 공개 식별자(없으면 null)
     * @param metadata   부가 JSON(없으면 null)
     */
    public static Notification inApp(Long userId, NotificationType type,
                                     String title, String body,
                                     NotificationTargetType targetType, String targetId,
                                     String metadata) {
        return new Notification(userId, type, NotificationChannel.IN_APP,
                title, body, targetType, targetId, metadata, NotificationStatus.SENT);
    }

    /**
     * 읽음으로 표시한다.
     *
     * <p><b>이미 읽었으면 최초 시각을 유지한다.</b> 폴링 클라이언트는 같은 알림을 여러 번
     * 읽음 처리로 보낼 수 있는데(즉시 push와 catch-up이 겹치는 구간), 그때마다 시각을 덮으면
     * "언제 처음 확인했나"를 잃는다. 덕분에 이 연산은 몇 번을 호출해도 결과가 같다.
     *
     * @param readAt 읽은 시각. 호출자가 주입해 테스트에서 시각을 고정할 수 있게 한다
     */
    public void markAsRead(Instant readAt) {
        if (this.readAt == null) {
            this.readAt = readAt;
        }
    }

    /** 읽었는지. 응답의 isRead가 이 값이다. */
    public boolean isRead() {
        return readAt != null;
    }
}
