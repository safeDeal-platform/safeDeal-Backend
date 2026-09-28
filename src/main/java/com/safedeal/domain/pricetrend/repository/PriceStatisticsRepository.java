package com.safedeal.domain.pricetrend.repository;

import com.safedeal.domain.pricetrend.entity.PriceStatistics;
import com.safedeal.domain.pricetrend.entity.StatPeriod;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 가격 통계 조회 리포지토리. 중분류 × 기간의 최신 집계 1건을 읽는 정적 쿼리라 파생 쿼리로
 * 충분하다.
 *
 * <p>{@link PriceStatistics}의 UNIQUE(category, period_start, period_end)가 같은 윈도우의
 * 재집계를 막아 보통은 (category, periodType)당 1행만 있다. 그래도
 * {@code OrderByCalculatedAtDesc}를 쓰는 이유는 배치가 upsert 대신 실수로 insert-only가
 * 되어 여러 행이 쌓여도 조회가 조용히 깨지지 않게 하기 위한 방어값이다(집계 배치 #10
 * 구현 시 재확인).
 */
public interface PriceStatisticsRepository extends JpaRepository<PriceStatistics, Long> {

    Optional<PriceStatistics> findFirstByCategoryAndPeriodTypeOrderByCalculatedAtDesc(
            String category, StatPeriod periodType);
}
