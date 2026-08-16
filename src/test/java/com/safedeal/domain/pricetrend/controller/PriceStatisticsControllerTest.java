package com.safedeal.domain.pricetrend.controller;

import com.safedeal.domain.pricetrend.dto.PriceStatisticsResponse;
import com.safedeal.domain.pricetrend.service.PriceStatisticsQueryService;
import com.safedeal.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 시세 조회 컨트롤러 테스트 — 서비스는 목(standalone, 비로그인).
 * 응답 계약·기본 기간(7d)·필수 파라미터 누락(400)·표본 부족 형태를 고정한다.
 */
class PriceStatisticsControllerTest {

    private final PriceStatisticsQueryService priceStatisticsQueryService = mock(PriceStatisticsQueryService.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new PriceStatisticsController(priceStatisticsQueryService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    @DisplayName("categoryCode·period로 조회하면 success/data 형식으로 시세를 내려준다")
    void getPriceStatistics_sufficient() throws Exception {
        PriceStatisticsResponse body = new PriceStatisticsResponse(
                "DIGITAL_MOBILE", "7d", 42, 900000, 700000, 1200000,
                Instant.parse("2026-08-16T00:00:00Z"), null);
        when(priceStatisticsQueryService.getPriceStatistics("DIGITAL_MOBILE", "7d")).thenReturn(body);

        mockMvc.perform(get("/api/price-statistics").param("categoryCode", "DIGITAL_MOBILE").param("period", "7d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.categoryCode").value("DIGITAL_MOBILE"))
                .andExpect(jsonPath("$.data.period").value("7d"))
                .andExpect(jsonPath("$.data.medianPrice").value(900000))
                .andExpect(jsonPath("$.data.message").doesNotExist());
    }

    @Test
    @DisplayName("period를 생략하면 기본값 7d로 서비스를 호출한다")
    void getPriceStatistics_defaultPeriod() throws Exception {
        when(priceStatisticsQueryService.getPriceStatistics(eq("DIGITAL_MOBILE"), eq("7d")))
                .thenReturn(PriceStatisticsResponse.insufficient(0));

        mockMvc.perform(get("/api/price-statistics").param("categoryCode", "DIGITAL_MOBILE"))
                .andExpect(status().isOk());

        verify(priceStatisticsQueryService).getPriceStatistics("DIGITAL_MOBILE", "7d");
    }

    @Test
    @DisplayName("표본 부족이면 가격 없이 표본 수 + 안내 메시지를 준다")
    void getPriceStatistics_insufficient() throws Exception {
        when(priceStatisticsQueryService.getPriceStatistics("NEW_CATEGORY", "7d"))
                .thenReturn(PriceStatisticsResponse.insufficient(1));

        mockMvc.perform(get("/api/price-statistics").param("categoryCode", "NEW_CATEGORY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sampleCount").value(1))
                .andExpect(jsonPath("$.data.message").value(PriceStatisticsResponse.INSUFFICIENT_MESSAGE))
                .andExpect(jsonPath("$.data.medianPrice").doesNotExist());
    }

    @Test
    @DisplayName("categoryCode가 없으면 400(C001)")
    void getPriceStatistics_missingCategory_isBadRequest() throws Exception {
        mockMvc.perform(get("/api/price-statistics"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("C001"));
    }
}
