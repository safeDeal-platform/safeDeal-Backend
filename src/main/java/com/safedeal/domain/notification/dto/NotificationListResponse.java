package com.safedeal.domain.notification.dto;

import com.safedeal.domain.notification.entity.Notification;

import java.util.List;

/**
 * 알림 목록 조회 응답.
 *
 * @param items    최신순(id DESC) 알림, 최대 10개.
 * @param latestId 이번 응답 중 가장 큰 id(다음 폴링의 sinceId). 결과가 비면 null.
 */
public record NotificationListResponse(
        List<NotificationItemResponse> items,
        Long latestId
) {

    /** latestId는 정렬 순서에 기대지 않고 배치의 최대 id로 직접 계산한다 — 순서가 안 뒤집혀도 값은 틀리지 않게. */
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
