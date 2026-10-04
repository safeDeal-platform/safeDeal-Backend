package com.safedeal.domain.pricetrend.repository;

import com.safedeal.domain.pricetrend.entity.PriceStatistics;
import com.safedeal.domain.pricetrend.entity.StatPeriod;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 가격 통계 조회 리포지토리. 중분류 × 기간의 최신 집계 1건을 읽는 정적 쿼리라 파생 쿼리로
 * 충분하다.
 *
 * <p>{@link PriceStatistics}의 UNIQUE(category, period_start, period_end)는 같은 윈도우의
 * 중복만 막는다. 따라서 서로 다른 윈도우에는 같은 (category, periodType)의 행이 여러 개
 * 있을 수 있으며, {@code OrderByCalculatedAtDesc}로 최신 집계를 선택한다(집계 배치 #10
 * 구현 시 재확인).
 */
public interface PriceStatisticsRepository extends JpaRepository<PriceStatistics, Long> {

    Optional<PriceStatistics> findFirstByCategoryAndPeriodTypeOrderByCalculatedAtDesc(
            String category, StatPeriod periodType);
}
