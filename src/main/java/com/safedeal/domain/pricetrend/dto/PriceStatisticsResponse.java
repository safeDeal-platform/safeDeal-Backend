package com.safedeal.domain.pricetrend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.safedeal.domain.pricetrend.entity.PriceStatistics;

import java.time.Instant;

/**
 * 시세 조회 응답. (API 명세서 — 시세 조회)
 *
 * 표본이 부족하면 가격을 감추고 표본 수와 안내 메시지만 준다 — 적은 표본으로 계산한 값을
 * "시세"로 오인하지 않게 하기 위해서다. {@code @JsonInclude(NON_NULL)}로 충분/부족 두 형태를
 * 한 DTO로 표현한다.
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
