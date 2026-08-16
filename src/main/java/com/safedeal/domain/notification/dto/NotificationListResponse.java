package com.safedeal.domain.notification.dto;

import com.safedeal.domain.notification.entity.Notification;

import java.util.List;

/**
 * 알림 목록 조회 응답. (API 명세서 — 알림 목록 조회)
 *
 * @param items    최신순(id DESC) 알림, 최대 10개.
 * @param latestId 이번 응답에서 가장 큰 알림 id. 클라이언트는 다음 폴링의 sinceId로 쓴다.
 *                 결과가 비면 null (직전 sinceId를 그대로 유지).
 */
public record NotificationListResponse(
        List<NotificationItemResponse> items,
        Long latestId
) {

    /**
     * id DESC로 정렬된 알림 목록을 응답으로 변환한다. 첫 항목이 가장 최신이므로 그 id가 latestId.
     */
    public static NotificationListResponse of(List<Notification> notifications) {
        List<NotificationItemResponse> items = notifications.stream()
                .map(NotificationItemResponse::from)
                .toList();
        Long latestId = items.isEmpty() ? null : items.get(0).notificationId();
        return new NotificationListResponse(items, latestId);
    }
}
