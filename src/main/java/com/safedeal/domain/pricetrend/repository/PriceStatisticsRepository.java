package com.safedeal.domain.pricetrend.repository;

import com.safedeal.domain.pricetrend.entity.PriceStatistics;
import com.safedeal.domain.pricetrend.entity.StatPeriod;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 가격 통계 조회 리포지토리.
 *
 * 시세 조회는 "중분류 × 기간의 가장 최근 집계 1건"을 읽는 정적 쿼리라 파생 쿼리로 충분하다
 * (정적=JPA). 집계가 같은 (category, periodType)에 대해 여러 시점 이력을 남기더라도
 * calculated_at 내림차순 첫 건이 최신값이다.
 */
public interface PriceStatisticsRepository extends JpaRepository<PriceStatistics, Long> {

    Optional<PriceStatistics> findFirstByCategoryAndPeriodTypeOrderByCalculatedAtDesc(
            String category, StatPeriod periodType);
}
