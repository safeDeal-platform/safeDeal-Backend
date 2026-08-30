package com.safedeal.domain.pricetrend.controller;

import com.safedeal.domain.pricetrend.dto.PriceStatisticsResponse;
import com.safedeal.domain.pricetrend.service.PriceStatisticsQueryService;
import com.safedeal.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 시세(가격추세) API. 시세 조회는 비로그인 허용이라 시큐리티 화이트리스트에 등록돼 있다
 * (global/config/SecurityConfig 참고).
 */
@RestController
@RequestMapping("/api/price-statistics")
@RequiredArgsConstructor
public class PriceStatisticsController {

    private final PriceStatisticsQueryService priceStatisticsQueryService;

    /**
     * 시세 조회 — 중분류 × 기간(7d/30d)의 최신 통계. 표본 부족 시 안내 메시지로 응답한다.
     *
     * @param categoryCode 중분류 code (필수)
     * @param period       "7d" 또는 "30d" (기본 7d)
     */
    @GetMapping
    public ApiResponse<PriceStatisticsResponse> getPriceStatistics(
            @RequestParam String categoryCode,
            @RequestParam(defaultValue = "7d") String period) {
        return ApiResponse.success(
                priceStatisticsQueryService.getPriceStatistics(categoryCode, period));
    }
}
