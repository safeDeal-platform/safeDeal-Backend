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
 * 사용자에게 전달되는 알림 한 건. 발송은 한 번뿐이지만, 읽었는지는 이 레코드에 계속
 * 남는다(read_at) — "읽음 처리가 없다"는 옛 설명을 보고 되돌리지 말 것.
 *
 * <p>target_id는 내부 PK가 아니라 공개 식별자 문자열(예: 매물 ULID)을 그대로 저장한다 —
 * 다른 도메인과 join 없이 응답을 조립하기 위해서다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "notifications",
        indexes = {
                // 사용자별 최신순 조회(목록/폴링)의 주 인덱스. id DESC로 커서(sinceId) 증분에도 쓰인다.
                @Index(name = "idx_notifications_user_id", columnList = "user_id, id"),
                @Index(name = "idx_notifications_target", columnList = "target_type, target_id"),
                // 안읽음 개수 배지 전용 — 폴링마다 도는 COUNT라 전용 인덱스를 둔다.
                @Index(name = "idx_notifications_unread", columnList = "user_id, channel, read_at")
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

    /** 읽은 시각. null이면 안 읽음 — 별도 boolean 대신 이 값 하나로 판단해 모순 상태를 막는다. */
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
     * 읽음으로 표시한다. 이미 읽었으면 최초 시각을 그대로 둔다 — 같은 요청이 여러 번 와도
     * 결과가 같아야 한다.
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
