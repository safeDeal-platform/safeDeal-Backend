package com.safedeal.domain.report.controller;

import com.safedeal.domain.report.service.ReportCommandService;
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

import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 신고 컨트롤러 테스트 — 서비스는 목, principal은 SecurityContext로 직접 주입한다(standalone,
 * {@code NotificationControllerTest}와 같은 전략). 응답 계약·principal→userId 바인딩·경로의
 * reportId(공개 id) 전달을 고정한다.
 */
class ReportControllerTest {

    private static final String PUBLIC_ID = "01J3ARSNIPREPORT0000000001";

    private final ReportCommandService reportCommandService = mock(ReportCommandService.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new ReportController(reportCommandService))
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

    @Test
    @DisplayName("취소는 principal의 userId와 경로의 공개 id로 서비스를 호출한다")
    void cancel_delegatesWithPrincipalUserIdAndPublicId() throws Exception {
        mockMvc.perform(patch("/api/reports/{reportId}/cancel", PUBLIC_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // userId를 본문·파라미터로 받지 않는다 — 받으면 남의 신고를 취소할 수 있다.
        verify(reportCommandService).cancel(42L, PUBLIC_ID);
    }

    @Test
    @DisplayName("없거나 남의 신고면 404(C002)로 내려간다")
    void cancel_notFound() throws Exception {
        doThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "신고를 찾을 수 없습니다."))
                .when(reportCommandService).cancel(42L, PUBLIC_ID);

        mockMvc.perform(patch("/api/reports/{reportId}/cancel", PUBLIC_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("C002"));
    }

    @Test
    @DisplayName("이미 검토 중이거나 처리된 신고면 409(C004)로 내려간다")
    void cancel_conflict() throws Exception {
        doThrow(new BusinessException(CommonErrorCode.CONFLICT, "이미 검토가 시작되었거나 처리된 신고는 취소할 수 없습니다."))
                .when(reportCommandService).cancel(42L, PUBLIC_ID);

        mockMvc.perform(patch("/api/reports/{reportId}/cancel", PUBLIC_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("C004"));
    }
}
