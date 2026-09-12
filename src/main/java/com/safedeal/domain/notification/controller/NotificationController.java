package com.safedeal.domain.notification.controller;

import com.safedeal.domain.notification.dto.NotificationListResponse;
import com.safedeal.domain.notification.service.NotificationCommandService;
import com.safedeal.domain.notification.service.NotificationQueryService;
import com.safedeal.global.response.ApiResponse;
import com.safedeal.global.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 알림 API. MVP 전달 채널은 IN_APP + 커서(sinceId) 폴링이다.
 *
 * 현재 사용자는 {@code @AuthenticationPrincipal AuthenticatedUser}로만 꺼낸다(공통 규칙).
 * 인증되지 않은 요청은 시큐리티 계층에서 401(C005)로 걸러진다 — 알림은 전부 개인 데이터라
 * 화이트리스트에 넣지 않는다.
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationQueryService notificationQueryService;
    private final NotificationCommandService notificationCommandService;

    /**
     * 알림 목록 조회. sinceId를 주면 그 id 초과분만(폴링 catch-up), 생략하면 최신 10개.
     *
     * @param sinceId 마지막으로 받은 알림 id. 다음 폴링 때 직전 응답의 latestId를 넣는다.
     */
    @GetMapping
    public ApiResponse<NotificationListResponse> getNotifications(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) Long sinceId) {
        return ApiResponse.success(
                notificationQueryService.getNotifications(user.userId(), sinceId));
    }

    /**
     * 알림 읽음 처리. 경로는 API 명세서('알림 읽음 처리')의 설계를 그대로 쓴다 — 그 항목은
     * 2026-08-02 정책으로 폐기 표시가 붙어 있으나, 정책이 2026-08-30에 "읽음 처리가 있어야
     * 함"으로 다시 확정됐다.
     *
     * <p>본문이 없고 서버가 시각을 찍으므로 PUT이 아니라 PATCH다. 이미 읽은 알림에 다시
     * 호출해도 200이며 최초 읽은 시각이 유지된다.
     */
    @PatchMapping("/{notificationId}/read")
    public ApiResponse<Void> markAsRead(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long notificationId) {
        notificationCommandService.markAsRead(user.userId(), notificationId);
        return ApiResponse.success();
    }
}
