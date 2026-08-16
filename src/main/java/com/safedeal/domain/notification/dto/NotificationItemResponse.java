package com.safedeal.domain.notification.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.safedeal.domain.notification.entity.Notification;
import com.safedeal.domain.notification.entity.NotificationTargetType;
import com.safedeal.domain.notification.entity.NotificationType;

import java.time.Instant;

/**
 * 알림 목록의 항목 하나. (API 명세서 — 알림 목록 조회 응답 계약)
 *
 * metadata·channel·status 등 내부 필드는 노출하지 않는다 — 클라이언트가 표시/딥링크에
 * 필요한 값만 내려준다. targetType/targetId는 이동 대상이 없으면 null이라 응답에서 생략된다.
 *
 * @param notificationId 알림 id. 클라이언트의 중복 제거(즉시 push ↔ 폴링 catch-up) 키.
 * @param targetId       딥링크 대상 공개 식별자(예: 매물 ULID). 대상이 없으면 null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NotificationItemResponse(
        Long notificationId,
        NotificationType type,
        String title,
        String body,
        NotificationTargetType targetType,
        String targetId,
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
                n.getCreatedAt()
        );
    }
}
