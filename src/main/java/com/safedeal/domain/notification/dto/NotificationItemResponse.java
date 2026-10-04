package com.safedeal.domain.notification.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.safedeal.domain.notification.entity.Notification;
import com.safedeal.domain.notification.entity.NotificationTargetType;
import com.safedeal.domain.notification.entity.NotificationType;

import java.time.Instant;

/**
 * 알림 목록의 항목 하나. metadata·channel·status 등 내부 필드는 내려주지 않는다.
 *
 * @param notificationId 알림 id. 클라이언트 중복 제거 키.
 * @param targetId       딥링크 대상 공개 식별자. 대상이 없으면 null.
 * @param isRead         읽음 여부만 내려준다 — 읽은 시각은 계약에 없다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NotificationItemResponse(
        Long notificationId,
        NotificationType type,
        String title,
        String body,
        NotificationTargetType targetType,
        String targetId,
        boolean isRead,
        Instant createdAt
) {

    public static NotificationItemResponse from(Notification n) {
        return new NotificationItemResponse(
                n.getId(),
                n.getType(),
                n.getTitle(),
                n.getBody(),
                n.getTargetType(),
                n.getTargetId(),
                n.isRead(),
                n.getCreatedAt()
        );
    }
}
