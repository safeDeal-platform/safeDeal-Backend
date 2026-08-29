package com.safedeal.domain.notification.dto;

import com.safedeal.domain.notification.entity.Notification;

import java.util.List;

/**
 * 알림 목록 조회 응답. (API 명세서 — 알림 목록 조회)
 *
 * @param items    최신순(id DESC) 알림, 최대 10개.
 * @param latestId 이번 응답에 담긴 알림 중 가장 큰 id. 클라이언트는 다음 폴링의 sinceId로 쓴다.
 *                 결과가 비면 null (직전 sinceId를 그대로 유지).
 */
public record NotificationListResponse(
        List<NotificationItemResponse> items,
        Long latestId
) {

    /**
     * 알림 목록을 응답으로 변환한다. {@code items}는 호출자가 넘긴 순서(최신순 계약)를 그대로
     * 따르되, {@code latestId}는 정렬 순서에 기대지 않고 배치 내 최대 id로 직접 계산한다 —
     * catch-up 경로(id ASC로 가져와 서비스에서 뒤집는 경로)가 실수로 순서를 안 뒤집어도
     * latestId만은 조용히 틀리지 않도록.
     */
    public static NotificationListResponse of(List<Notification> notifications) {
        List<NotificationItemResponse> items = notifications.stream()
                .map(NotificationItemResponse::from)
                .toList();
        Long latestId = notifications.stream()
                .map(Notification::getId)
                .max(Long::compareTo)
                .orElse(null);
        return new NotificationListResponse(items, latestId);
    }
}
