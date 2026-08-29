package com.safedeal.domain.pricetrend.service;

import com.safedeal.domain.pricetrend.dto.PriceStatisticsResponse;
import com.safedeal.domain.pricetrend.entity.PriceStatistics;
import com.safedeal.domain.pricetrend.entity.StatPeriod;
import com.safedeal.domain.pricetrend.repository.PriceStatisticsRepository;
import com.safedeal.global.exception.BusinessException;
import com.safedeal.global.exception.CommonErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 시세 조회 서비스 단위 테스트 — 표본 충분/부족/없음 분기와 기간 code 파싱을 DB 없이 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class PriceStatisticsQueryServiceTest {

    @Mock
    PriceStatisticsRepository priceStatisticsRepository;

    @InjectMocks
    PriceStatisticsQueryService priceStatisticsQueryService;

    private static final String CATEGORY = "DIGITAL_MOBILE";

    private static PriceStatistics stat(int sampleCount) {
        return PriceStatistics.builder()
                .category(CATEGORY)
                .categorySnapshot(CATEGORY)
                .periodType(StatPeriod.D7)
                .periodStart(LocalDate.of(2026, 8, 9))
                .periodEnd(LocalDate.of(2026, 8, 16))
                .sampleCount(sampleCount)
                .avgPrice(920000)
                .medianPrice(900000)
                .minPrice(700000)
                .maxPrice(1200000)
                .stdDev(new BigDecimal("12345.67"))
                .calculatedAt(Instant.parse("2026-08-16T00:00:00Z"))
                .build();
    }

    @Test
    @DisplayName("표본이 충분하면 median/min/max와 기간 code를 담아 응답한다")
    void sufficient() {
        when(priceStatisticsRepository.findFirstByCategoryAndPeriodTypeOrderByCalculatedAtDesc(CATEGORY, StatPeriod.D7))
                .thenReturn(Optional.of(stat(42)));

        PriceStatisticsResponse response = priceStatisticsQueryService.getPriceStatistics(CATEGORY, "7d");

        assertThat(response.categoryCode()).isEqualTo(CATEGORY);
        assertThat(response.period()).isEqualTo("7d");
        assertThat(response.sampleCount()).isEqualTo(42);
        assertThat(response.medianPrice()).isEqualTo(900000);
        assertThat(response.minPrice()).isEqualTo(700000);
        assertThat(response.maxPrice()).isEqualTo(1200000);
        assertThat(response.message()).isNull();
    }

    @Test
    @DisplayName("표본이 신뢰 기준 미만이면 가격을 감추고 요청 컨텍스트 + 표본 수 + 안내만 준다")
    void insufficientLowSample() {
        when(priceStatisticsRepository.findFirstByCategoryAndPeriodTypeOrderByCalculatedAtDesc(CATEGORY, StatPeriod.D7))
                .thenReturn(Optional.of(stat(1)));

        PriceStatisticsResponse response = priceStatisticsQueryService.getPriceStatistics(CATEGORY, "7d");

        assertThat(response.sampleCount()).isEqualTo(1);
        assertThat(response.message()).isEqualTo(PriceStatisticsResponse.INSUFFICIENT_MESSAGE);
        assertThat(response.medianPrice()).isNull();
        assertThat(response.categoryCode()).isEqualTo(CATEGORY);
        assertThat(response.period()).isEqualTo("7d");
    }

    @Test
    @DisplayName("집계가 아예 없으면 요청 컨텍스트 + 표본 0 + 안내로 응답한다")
    void insufficientNoRow() {
        when(priceStatisticsRepository.findFirstByCategoryAndPeriodTypeOrderByCalculatedAtDesc(CATEGORY, StatPeriod.D30))
                .thenReturn(Optional.empty());

        PriceStatisticsResponse response = priceStatisticsQueryService.getPriceStatistics(CATEGORY, "30d");

        assertThat(response.sampleCount()).isZero();
        assertThat(response.message()).isEqualTo(PriceStatisticsResponse.INSUFFICIENT_MESSAGE);
        assertThat(response.categoryCode()).isEqualTo(CATEGORY);
        assertThat(response.period()).isEqualTo("30d");
    }

    @Test
    @DisplayName("표본 수가 신뢰 기준 바로 아래(경계-1)면 부족으로 처리한다")
    void insufficientAtBoundaryMinusOne() {
        when(priceStatisticsRepository.findFirstByCategoryAndPeriodTypeOrderByCalculatedAtDesc(CATEGORY, StatPeriod.D7))
                .thenReturn(Optional.of(stat(PriceStatisticsQueryService.MIN_RELIABLE_SAMPLE_COUNT - 1)));

        PriceStatisticsResponse response = priceStatisticsQueryService.getPriceStatistics(CATEGORY, "7d");

        assertThat(response.message()).isEqualTo(PriceStatisticsResponse.INSUFFICIENT_MESSAGE);
        assertThat(response.medianPrice()).isNull();
    }

    @Test
    @DisplayName("표본 수가 신뢰 기준과 정확히 같으면 충분으로 처리한다")
    void sufficientAtBoundary() {
        when(priceStatisticsRepository.findFirstByCategoryAndPeriodTypeOrderByCalculatedAtDesc(CATEGORY, StatPeriod.D7))
                .thenReturn(Optional.of(stat(PriceStatisticsQueryService.MIN_RELIABLE_SAMPLE_COUNT)));

        PriceStatisticsResponse response = priceStatisticsQueryService.getPriceStatistics(CATEGORY, "7d");

        assertThat(response.message()).isNull();
        assertThat(response.medianPrice()).isEqualTo(900000);
    }

    @Test
    @DisplayName("지원하지 않는 기간 code면 조회 전에 400(INVALID_INPUT)을 던진다")
    void invalidPeriod() {
        assertThatThrownBy(() -> priceStatisticsQueryService.getPriceStatistics(CATEGORY, "1y"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.INVALID_INPUT);

        verifyNoInteractions(priceStatisticsRepository);
    }
}
