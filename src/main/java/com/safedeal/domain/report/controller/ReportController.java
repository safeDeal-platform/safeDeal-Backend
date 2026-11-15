package com.safedeal.domain.report.controller;

import com.safedeal.domain.report.service.ReportCommandService;
import com.safedeal.global.response.ApiResponse;
import com.safedeal.global.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 신고 API. 현재 사용자는 {@code @AuthenticationPrincipal AuthenticatedUser}로만 꺼낸다(공통 규칙).
 * 인증되지 않은 요청은 시큐리티 계층에서 401(C005)로 걸러진다.
 */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportCommandService reportCommandService;

    /**
     * 신고 취소. 본문이 없고 서버가 상태만 바꾸므로 PATCH다({@code NotificationController.markAsRead}와
     * 같은 이유). 경로의 {@code reportId}는 내부 PK가 아니라 공개 id(ULID)다 — 응답에 내려준 값 그대로다.
     */
    @PatchMapping("/{reportId}/cancel")
    public ApiResponse<Void> cancel(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String reportId) {
        reportCommandService.cancel(user.userId(), reportId);
        return ApiResponse.success();
    }
}
