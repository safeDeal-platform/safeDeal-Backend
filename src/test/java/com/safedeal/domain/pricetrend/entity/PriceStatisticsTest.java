package com.safedeal.domain.pricetrend.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * categorySnapshot이 category와 별개로 채워질 수 있고, categories 마스터 테이블 도입 전
 * 지금은 null로 둬도 생성 가능함을 고정하는 단위 테스트(DB 불필요).
 */
class PriceStatisticsTest {

    private static PriceStatistics.PriceStatisticsBuilder base() {
        return PriceStatistics.builder()
                .category("DIGITAL_PHONE")
                .periodType(StatPeriod.D7)
                .periodStart(LocalDate.of(2026, 8, 9))
                .periodEnd(LocalDate.of(2026, 8, 16))
                .sampleCount(10)
                .avgPrice(1000000)
                .medianPrice(1000000)
                .minPrice(900000)
                .maxPrice(1100000)
                .stdDev(BigDecimal.ZERO)
                .calculatedAt(Instant.parse("2026-08-16T00:00:00Z"));
    }

    @Test
    @DisplayName("categories 마스터 테이블 도입 전(현재)엔 categorySnapshot 없이도 생성된다 — null이 유효한 상태")
    void categorySnapshotDefaultsToNull() {
        PriceStatistics stat = base().build();

        assertThat(stat.getCategory()).isEqualTo("DIGITAL_PHONE");
        assertThat(stat.getCategorySnapshot()).isNull();
    }

    @Test
    @DisplayName("categorySnapshot은 category와 독립적으로 다른 값을 가질 수 있다 — category(leaf code) 복사가 아니다")
    void categorySnapshotIsIndependentFromCategory() {
        PriceStatistics stat = base()
                .categorySnapshot("DIGITAL>DIGITAL_PHONE") // categories 도입 후 실제 형식 예시
                .build();

        assertThat(stat.getCategory()).isEqualTo("DIGITAL_PHONE");
        assertThat(stat.getCategorySnapshot()).isEqualTo("DIGITAL>DIGITAL_PHONE");
        assertThat(stat.getCategorySnapshot()).isNotEqualTo(stat.getCategory());
    }
}
