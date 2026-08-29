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
 * 중분류 × 기간(7d/30d)별 가격 통계 한 건. 배치(Spring Batch)/관리자 트리거가 구매확정된
 * 거래(PaymentCompleted)를 원료로 집계해 upsert한다.
 *
 * <p>감사 필드로 created_at/updated_at을 두지 않고 {@code calculatedAt}(집계 시각)을 쓴다 —
 * 이 행의 의미 있는 시각은 "언제 계산됐는가"이고, API 응답도 이 값을 내려준다.
 *
 * <p><b>스키마 메모(periodType):</b> V1 초안은 (category, period_start, period_end)만 두었으나,
 * 시세 조회가 "7d/30d 중 최신값"을 바로 찾도록 {@code periodType} 판별 컬럼을 추가했다.
 * 날짜 구간 차이로 기간 종류를 역산하지 않기 위함이다. (MVP 동안 스키마 진실 = 엔티티,
 * V1 확정 시 팀과 반영)
 *
 * <p><b>스키마 메모(categorySnapshot):</b> 노션 정책(상품·유저 → 카테고리·지역, 확정
 * 2026-08-02) — "부모를 바꾸면 과거 통계 해석이 소급 변경되므로 price_statistics는 집계
 * 시점 분류를 보존한다." {@code category}(leaf code)는 categories 마스터 테이블의 최신
 * 상태를 참조하는 값이라 나중에 parent가 바뀌면 과거 집계 행의 의미까지 바뀐다 —
 * {@code categorySnapshot}은 집계 당시의 분류를 그대로 얼려 둔다.
 * categories 마스터 테이블이 아직 코드에 없어(중현/상품 도메인 소유, 이 PR 범위 밖) 정확한
 * 스냅샷 형식(대분류&gt;중분류 경로 vs parent code+leaf code)은 미확정이다 — 지금은 집계
 * 시점의 leaf category code를 그대로 담는다. categories 도입 시 형식을 재확정한다.
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

    /** 중분류 code (예: DIGITAL_MOBILE). categories 마스터 테이블의 최신 상태를 가리킨다. */
    @Column(nullable = false, length = 30)
    private String category;

    /** 집계 시점의 카테고리 분류 스냅샷(정책: 부모 변경으로 과거 통계가 소급 변경되지 않도록). */
    @Column(name = "category_snapshot", nullable = false, length = 30)
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
