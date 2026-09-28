package com.safedeal.domain.pricetrend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 중분류 × 기간(7d/30d)별 가격 통계 한 건. 배치가 구매확정 거래를 집계해 upsert한다.
 *
 * created_at/updated_at 대신 {@code calculatedAt}(집계 시각)을 쓴다 — 이 행에서 의미 있는
 * 시각은 "언제 계산됐는가"이기 때문이다. {@code periodType}은 "7d/30d 중 최신값"을 날짜
 * 구간으로 역산하지 않고 바로 찾기 위한 판별 컬럼이다.
 *
 * <p>{@code categorySnapshot}은 지금 항상 null이다 — categories 마스터 테이블이 아직 없어
 * (상품 도메인 소유, 이 PR 범위 밖) leaf code를 그대로 복사해도 나중에 parent가 바뀌면
 * 소급 변경을 못 막는 가짜 값이 된다. <b>category 값을 복사해 채우지 말 것.</b> categories
 * 도입 후 집계 배치(#10)가 실제 부모 경로를 채운다 (deferred #13).
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "price_statistics",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_price_statistics_category_period",
                columnNames = {"category", "period_start", "period_end"}),
        indexes = @Index(
                name = "idx_price_statistics_category_period_type",
                columnList = "category, period_type, calculated_at")
)
public class PriceStatistics {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 중분류 code (예: DIGITAL_PHONE). categories 마스터 테이블의 최신 상태를 가리킨다. */
    @Column(nullable = false, length = 30)
    private String category;

    /**
     * 집계 시점의 카테고리 분류 스냅샷(정책: 부모 변경으로 과거 통계가 소급 변경되지 않도록).
     * categories 마스터 테이블 도입 전까지는 항상 null — 클래스 주석 참고.
     */
    @Column(name = "category_snapshot", length = 100)
    private String categorySnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "period_type", nullable = false, length = 10)
    private StatPeriod periodType;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    /** 집계 표본 수(거래 건수). 시세 조회 응답에 항상 표기된다. */
    @Column(name = "sample_count", nullable = false)
    private int sampleCount;

    @Column(name = "avg_price", nullable = false)
    private int avgPrice;

    @Column(name = "median_price", nullable = false)
    private int medianPrice;

    @Column(name = "min_price", nullable = false)
    private int minPrice;

    @Column(name = "max_price", nullable = false)
    private int maxPrice;

    @Column(name = "std_dev", nullable = false, precision = 10, scale = 2)
    private BigDecimal stdDev;

    @Column(name = "calculated_at", nullable = false)
    private Instant calculatedAt;

    @Builder
    private PriceStatistics(String category, String categorySnapshot, StatPeriod periodType,
                            LocalDate periodStart, LocalDate periodEnd,
                            int sampleCount, int avgPrice, int medianPrice,
                            int minPrice, int maxPrice, BigDecimal stdDev,
                            Instant calculatedAt) {
        this.category = category;
        this.categorySnapshot = categorySnapshot;
        this.periodType = periodType;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.sampleCount = sampleCount;
        this.avgPrice = avgPrice;
        this.medianPrice = medianPrice;
        this.minPrice = minPrice;
        this.maxPrice = maxPrice;
        this.stdDev = stdDev;
        this.calculatedAt = calculatedAt;
    }
}
