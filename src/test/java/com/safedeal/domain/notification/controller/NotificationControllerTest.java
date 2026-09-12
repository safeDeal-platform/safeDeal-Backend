package com.safedeal.domain.notification.controller;

import com.safedeal.domain.notification.dto.NotificationItemResponse;
import com.safedeal.domain.notification.dto.NotificationListResponse;
import com.safedeal.domain.notification.entity.NotificationTargetType;
import com.safedeal.domain.notification.entity.NotificationType;
import com.safedeal.domain.notification.service.NotificationCommandService;
import com.safedeal.domain.notification.service.NotificationQueryService;
import com.safedeal.global.exception.BusinessException;
import com.safedeal.global.exception.CommonErrorCode;
import com.safedeal.global.exception.GlobalExceptionHandler;
import com.safedeal.global.security.AuthenticatedUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 알림 컨트롤러 테스트 — 서비스는 목, principal은 SecurityContext로 직접 주입한다(standalone).
 * 응답 계약(success/data 형식)·principal→userId 바인딩·sinceId 전달을 고정한다.
 *
 * 실제 {@code @AuthenticationPrincipal} 리졸버를 등록해, 컨트롤러가 principal에서 userId를
 * 꺼내는 표준 경로(공통 규칙)를 그대로 검증한다.
 */
class NotificationControllerTest {

    private final NotificationQueryService notificationQueryService = mock(NotificationQueryService.class);
    private final NotificationCommandService notificationCommandService = mock(NotificationCommandService.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new NotificationController(notificationQueryService, notificationCommandService))
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @BeforeEach
    void authenticate() {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(42L, "USER"), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static NotificationListResponse sample() {
        return new NotificationListResponse(
                List.of(new NotificationItemResponse(
                        88L, NotificationType.PRICE_DROP, "찜한 매물 가격 인하",
                        "아이폰 15 프로가 90만원으로 내려갔어요",
                        NotificationTargetType.LISTING, "01J3ARSNIP",
                        false,
                        Instant.parse("2026-08-16T00:00:00Z"))),
                88L);
    }

    @Test
    @DisplayName("sinceId 없이 조회하면 success/data 형식으로 내려오고 서비스는 sinceId=null로 호출된다")
    void getNotifications_withoutSinceId() throws Exception {
        when(notificationQueryService.getNotifications(42L, null)).thenReturn(sample());

        mockMvc.perform(get("/api/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items[0].notificationId").value(88))
                .andExpect(jsonPath("$.data.items[0].type").value("PRICE_DROP"))
                .andExpect(jsonPath("$.data.items[0].targetId").value("01J3ARSNIP"))
                .andExpect(jsonPath("$.data.latestId").value(88));

        verify(notificationQueryService).getNotifications(42L, null);
    }

    @Test
    @DisplayName("안 읽은 알림도 isRead 필드를 내려준다 — 필드가 생략되면 안 된다")
    void getNotifications_exposesIsReadEvenWhenUnread() throws Exception {
        when(notificationQueryService.getNotifications(42L, null)).thenReturn(sample());

        // NON_NULL 설정 때문에 null 필드는 응답에서 빠진다. isRead가 primitive가 아니라
        // Boolean이었다면 안 읽은 알림에서 필드 자체가 사라져 클라이언트가 구분하지 못한다.
        mockMvc.perform(get("/api/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].isRead").value(false));
    }

    @Test
    @DisplayName("sinceId를 주면 그 값이 서비스로 전달된다")
    void getNotifications_withSinceId() throws Exception {
        when(notificationQueryService.getNotifications(42L, 88L)).thenReturn(sample());

        mockMvc.perform(get("/api/notifications").param("sinceId", "88"))
                .andExpect(status().isOk());

        verify(notificationQueryService).getNotifications(42L, 88L);
    }

    @Test
    @DisplayName("읽음 처리는 principal의 userId와 경로의 알림 id로 서비스를 호출한다")
    void markAsRead_delegatesWithPrincipalUserId() throws Exception {
        mockMvc.perform(patch("/api/notifications/88/read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // userId를 본문·파라미터로 받지 않는다 — 받으면 남의 알림을 읽음 처리할 수 있다.
        verify(notificationCommandService).markAsRead(42L, 88L);
    }

    @Test
    @DisplayName("없거나 남의 알림이면 404 C002로 내려간다")
    void markAsRead_notFound() throws Exception {
        doThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "알림을 찾을 수 없습니다."))
                .when(notificationCommandService).markAsRead(42L, 99L);

        mockMvc.perform(patch("/api/notifications/99/read"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("C002"));
    }
}
