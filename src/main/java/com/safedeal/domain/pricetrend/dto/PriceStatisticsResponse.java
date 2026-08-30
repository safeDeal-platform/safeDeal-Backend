package com.safedeal.domain.pricetrend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.safedeal.domain.pricetrend.entity.PriceStatistics;

import java.time.Instant;

/**
 * 시세 조회 응답. (API 명세서 — 시세 조회)
 *
 * MVP는 단순 최근값 표시라 median/min/max만 내려준다(구간값·변동폭%는 이후). 표본이 부족하면
 * 가격을 감추고 표본 수 + 안내 메시지만 준다 — 신뢰할 수 없는 소수 표본으로 "시세"를 오인하게
 * 만들지 않기 위함이다.
 *
 * {@code @JsonInclude(NON_NULL)}로 두 형태를 한 DTO로 표현한다:
 * <ul>
 *   <li>충분: {@code {categoryCode, period, sampleCount, medianPrice, minPrice, maxPrice, calculatedAt}}</li>
 *   <li>부족: {@code {categoryCode, period, sampleCount, message}}</li>
 * </ul>
 *
 * <p>부족 응답도 요청받은 categoryCode/period를 그대로 돌려준다 — 클라이언트가 여러 카테고리를
 * 병렬 조회할 때 응답을 요청에 매칭하고 캐시 키로 쓸 수 있어야 하기 때문이다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PriceStatisticsResponse(
        String categoryCode,
        String period,
        int sampleCount,
        Integer medianPrice,
        Integer minPrice,
        Integer maxPrice,
        Instant calculatedAt,
        String message
) {

    public static final String INSUFFICIENT_MESSAGE = "해당 매물에 대한 시세 정보 부족";

    /** 표본이 충분한 경우 — 중분류 code와 기간 code(7d/30d)를 함께 담아 응답한다. */
    public static PriceStatisticsResponse of(PriceStatistics stat, String categoryCode, String periodCode) {
        return new PriceStatisticsResponse(
                categoryCode,
                periodCode,
                stat.getSampleCount(),
                stat.getMedianPrice(),
                stat.getMinPrice(),
                stat.getMaxPrice(),
                stat.getCalculatedAt(),
                null);
    }

    /** 표본 부족(신규 제품 등) — 가격은 감추고 요청 컨텍스트(categoryCode/period)와 표본 수, 안내만 준다. */
    public static PriceStatisticsResponse insufficient(String categoryCode, String periodCode, int sampleCount) {
        return new PriceStatisticsResponse(
                categoryCode, periodCode, sampleCount, null, null, null, null, INSUFFICIENT_MESSAGE);
    }
}
