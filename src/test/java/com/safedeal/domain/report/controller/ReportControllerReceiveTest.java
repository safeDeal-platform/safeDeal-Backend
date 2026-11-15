package com.safedeal.domain.report.controller;

import com.safedeal.domain.report.dto.ReportCreateRequest;
import com.safedeal.domain.report.dto.ReportCreateResponse;
import com.safedeal.domain.report.entity.ReportReason;
import com.safedeal.domain.report.entity.ReportStatus;
import com.safedeal.domain.report.entity.ReportTargetType;
import com.safedeal.domain.report.exception.ReportErrorCode;
import com.safedeal.domain.report.service.ReportCommandService;
import com.safedeal.global.exception.BusinessException;
import com.safedeal.global.exception.CommonErrorCode;
import com.safedeal.global.exception.GlobalExceptionHandler;
import com.safedeal.global.security.AuthenticatedUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 신고 접수(POST /api/reports) — 기존 {@link ReportController}에 추가되는 접수 엔드포인트(결정 D1).
 * 서비스는 목, principal은 SecurityContext로 직접 넣는다({@code ReportControllerTest}와 같은 standalone 구성,
 * 취소 테스트와 파일만 나눴다). 요청 형식 검증(@Valid)·응답 계약(201, 필드
 * 화이트리스트)·principal→userId 바인딩을 고정한다. 인증 없음(401)은 시큐리티 필터가 있어야 해서
 * {@code ReportCreateApiTest}가 본다.
 */
class ReportControllerReceiveTest {

    private static final String LISTING_ID = "01J3ARSNIPLISTING000000001";
    private static final ReportCreateResponse RECEIVED = new ReportCreateResponse(
            "01J3ARSNIPREPORT0000000001", ReportStatus.RECEIVED, Instant.parse("2026-11-15T01:00:00Z"));

    private final ReportCommandService reportCommandService = mock(ReportCommandService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

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

    /** 정상 매물 신고 본문. 각 테스트는 필요한 필드만 바꾸거나 지운다(값을 null로 두면 필드 자체를 뺀다). */
    private static Map<String, Object> listingBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetType", "LISTING");
        body.put("targetId", LISTING_ID);
        body.put("reasonCode", "FRAUD");
        body.put("detail", "허위 매물입니다");
        return body;
    }

    private ResultActions postReport(Map<String, Object> body) throws Exception {
        body.values().removeIf(v -> v == null);
        return mockMvc.perform(post("/api/reports")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(body)));
    }

    private void assertBadRequest(Map<String, Object> body) throws Exception {
        postReport(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("C001"));
        verify(reportCommandService, never()).receive(any(), any());
    }

    @Test
    @DisplayName("RPT-C01 정상 접수는 201이고, principal의 userId와 본문을 그대로 서비스에 넘긴다")
    void createsWith201() throws Exception {
        ReportCreateRequest expected = new ReportCreateRequest(
                ReportTargetType.LISTING, LISTING_ID, ReportReason.FRAUD, "허위 매물입니다", null);
        when(reportCommandService.receive(42L, expected)).thenReturn(RECEIVED);

        postReport(listingBody())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.reportId").value("01J3ARSNIPREPORT0000000001"))
                .andExpect(jsonPath("$.data.status").value("RECEIVED"))
                .andExpect(jsonPath("$.data.receivedAt").value("2026-11-15T01:00:00Z"));

        verify(reportCommandService).receive(42L, expected);
    }

    @Test
    @DisplayName("RPT-C02 채팅 신고는 증거 메시지 id를 숫자 그대로 넘긴다")
    void passesEvidenceMessageId() throws Exception {
        ReportCreateRequest expected = new ReportCreateRequest(
                ReportTargetType.CHAT, "01J3ARSNIPROOM000000000001", ReportReason.ABUSE, null, 500L);
        when(reportCommandService.receive(42L, expected)).thenReturn(RECEIVED);
        Map<String, Object> body = listingBody();
        body.put("targetType", "CHAT");
        body.put("targetId", "01J3ARSNIPROOM000000000001");
        body.put("reasonCode", "ABUSE");
        body.put("detail", null);
        body.put("evidenceMessageId", 500);

        postReport(body).andExpect(status().isCreated());

        verify(reportCommandService).receive(42L, expected);
    }

    @Test
    @DisplayName("RPT-C30 응답 data에는 reportId·status·receivedAt 세 필드만 있다 - 피신고자·내부 id 미포함")
    void responseFieldsAreWhitelisted() throws Exception {
        when(reportCommandService.receive(eq(42L), any())).thenReturn(RECEIVED);

        postReport(listingBody())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data.reportId").value("01J3ARSNIPREPORT0000000001"))
                .andExpect(jsonPath("$.data.status").value("RECEIVED"))
                .andExpect(jsonPath("$.data.receivedAt").value("2026-11-15T01:00:00Z"));
    }

    @Test
    @DisplayName("RPT-C17 targetType이 enum 값이 아니면 400(C001)")
    void unknownTargetTypeIsBadRequest() throws Exception {
        Map<String, Object> body = listingBody();
        body.put("targetType", "USER");

        assertBadRequest(body);
    }

    @Test
    @DisplayName("RPT-C17 reasonCode가 enum 값이 아니면 400(C001)")
    void unknownReasonIsBadRequest() throws Exception {
        Map<String, Object> body = listingBody();
        body.put("reasonCode", "HATE");

        assertBadRequest(body);
    }

    @Test
    @DisplayName("RPT-C17 targetType이 없으면 400(C001)")
    void missingTargetTypeIsBadRequest() throws Exception {
        Map<String, Object> body = listingBody();
        body.put("targetType", null);

        assertBadRequest(body);
    }

    @Test
    @DisplayName("RPT-C17 targetId가 없으면 400(C001)")
    void missingTargetIdIsBadRequest() throws Exception {
        Map<String, Object> body = listingBody();
        body.put("targetId", null);

        assertBadRequest(body);
    }

    @Test
    @DisplayName("RPT-C17 reasonCode가 없으면 400(C001)")
    void missingReasonIsBadRequest() throws Exception {
        Map<String, Object> body = listingBody();
        body.put("reasonCode", null);

        assertBadRequest(body);
    }

    @Test
    @DisplayName("RPT-C27 targetId가 25자면 400(C001)")
    void shortTargetIdIsBadRequest() throws Exception {
        Map<String, Object> body = listingBody();
        body.put("targetId", LISTING_ID.substring(1));

        assertBadRequest(body);
    }

    @Test
    @DisplayName("RPT-C27 targetId가 27자면 400(C001)")
    void longTargetIdIsBadRequest() throws Exception {
        Map<String, Object> body = listingBody();
        body.put("targetId", LISTING_ID + "1");

        assertBadRequest(body);
    }

    @Test
    @DisplayName("RPT-C20 상세 내용이 정확히 1,000자면 통과해 201")
    void detailOf1000IsAccepted() throws Exception {
        when(reportCommandService.receive(eq(42L), any())).thenReturn(RECEIVED);
        Map<String, Object> body = listingBody();
        body.put("detail", "가".repeat(1000));

        postReport(body).andExpect(status().isCreated());

        verify(reportCommandService).receive(42L, new ReportCreateRequest(
                ReportTargetType.LISTING, LISTING_ID, ReportReason.FRAUD, "가".repeat(1000), null));
    }

    @Test
    @DisplayName("RPT-C21 상세 내용이 1,001자면 400(C001)")
    void detailOf1001IsBadRequest() throws Exception {
        Map<String, Object> body = listingBody();
        body.put("detail", "가".repeat(1001));

        assertBadRequest(body);
    }

    @Test
    @DisplayName("RPT-C22 상세 내용을 생략해도 201이고 detail=null로 넘긴다")
    void missingDetailIsAccepted() throws Exception {
        when(reportCommandService.receive(eq(42L), any())).thenReturn(RECEIVED);
        Map<String, Object> body = listingBody();
        body.put("detail", null);

        postReport(body).andExpect(status().isCreated());

        verify(reportCommandService).receive(42L, new ReportCreateRequest(
                ReportTargetType.LISTING, LISTING_ID, ReportReason.FRAUD, null, null));
    }

    /** 컨트롤러는 detail을 다듬지 않는다 — 공백→null 변환(Q6·D4)은 서비스·엔티티 몫이라 원문 그대로 넘겨야 한다. */
    private void assertDetailPassedAsIs(String detail) throws Exception {
        when(reportCommandService.receive(eq(42L), any())).thenReturn(RECEIVED);
        Map<String, Object> body = listingBody();
        body.put("detail", detail);

        postReport(body).andExpect(status().isCreated());

        verify(reportCommandService).receive(42L, new ReportCreateRequest(
                ReportTargetType.LISTING, LISTING_ID, ReportReason.FRAUD, detail, null));
    }

    @Test
    @DisplayName("RPT-C23 상세 내용이 빈 문자열이어도 201이고 \"\" 그대로 서비스에 넘긴다")
    void emptyDetailIsPassedAsIs() throws Exception {
        assertDetailPassedAsIs("");
    }

    @Test
    @DisplayName("RPT-C23 상세 내용이 공백뿐이어도 201이고 공백 그대로 서비스에 넘긴다")
    void blankDetailIsPassedAsIs() throws Exception {
        assertDetailPassedAsIs("   ");
    }

    @Test
    @DisplayName("RPT-C48 1,000자 검사는 원문 기준이다 - 앞뒤 공백 포함 1,001자면 400(C001) (D4)")
    void lengthIsCheckedOnRawDetail() throws Exception {
        Map<String, Object> body = listingBody();
        body.put("detail", " " + "가".repeat(1000));

        assertBadRequest(body);
    }

    @Test
    @DisplayName("RPT-C25 하루 한도 초과는 429(RPT003)로 내려간다")
    void dailyLimitIsTooManyRequests() throws Exception {
        when(reportCommandService.receive(eq(42L), any()))
                .thenThrow(new BusinessException(ReportErrorCode.DAILY_REPORT_LIMIT_EXCEEDED));

        postReport(listingBody())
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RPT003"));
    }

    @Test
    @DisplayName("RPT-C43 부활 잠금 타임아웃은 409(C009)로 내려간다")
    void lockTimeoutIsConcurrentModification() throws Exception {
        when(reportCommandService.receive(eq(42L), any()))
                .thenThrow(new BusinessException(CommonErrorCode.CONCURRENT_MODIFICATION));

        postReport(listingBody())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("C009"));
    }

    @Test
    @DisplayName("RPT-C44 제약 이름을 판정할 수 없는 무결성 위반은 409(C004)로 내려간다")
    void unknownIntegrityViolationIsConflict() throws Exception {
        when(reportCommandService.receive(eq(42L), any()))
                .thenThrow(new DataIntegrityViolationException("원인 불명"));

        postReport(listingBody())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("C004"));
    }
}
