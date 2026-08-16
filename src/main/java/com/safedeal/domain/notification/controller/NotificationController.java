package com.safedeal.domain.notification.controller;

import com.safedeal.domain.notification.dto.NotificationListResponse;
import com.safedeal.domain.notification.service.NotificationQueryService;
import com.safedeal.global.response.ApiResponse;
import com.safedeal.global.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 알림 API. MVP 전달 채널은 IN_APP + 커서(sinceId) 폴링이다.
 *
 * 현재 사용자는 {@code @AuthenticationPrincipal AuthenticatedUser}로만 꺼낸다(공통 규칙).
 * 인증되지 않은 요청은 시큐리티 계층에서 401(C005)로 걸러진다.
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationQueryService notificationQueryService;

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
}
