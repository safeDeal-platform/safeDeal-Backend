package com.safedeal.domain.pricetrend.repository;

import com.safedeal.domain.pricetrend.entity.PriceStatistics;
import com.safedeal.domain.pricetrend.entity.StatPeriod;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 가격 통계 조회 리포지토리.
 *
 * 시세 조회는 "중분류 × 기간의 최신 집계 1건"을 읽는 정적 쿼리라 파생 쿼리로 충분하다
 * (정적=JPA).
 *
 * <p><b>upsert 전제(엔티티 참고):</b> {@link PriceStatistics}의
 * {@code uk_price_statistics_category_period} UNIQUE(category, period_start, period_end)가
 * 같은 윈도우의 재집계를 막으므로, 같은 (category, periodType)에는 최신 윈도우 1행만
 * 존재한다 — 이력(과거 윈도우 포함 여러 행)을 남기는 모델이 아니다. 이 메서드가
 * {@code OrderByCalculatedAtDesc}로 "첫 건"을 고르는 이유는 이력에서 최신을 추리기
 * 위함이 아니라, 배치가 upsert 대신 실수로 insert-only가 되어 같은 (category, periodType)에
 * 여러 행이 쌓이는 경우에도 조회가 조용히 깨지지 않도록 하는 방어값이다. 집계(#10) 구현 시
 * upsert 여부를 다시 확정하고, 이 코멘트와 UNIQUE 제약을 함께 갱신한다.
 */
public interface PriceStatisticsRepository extends JpaRepository<PriceStatistics, Long> {

    Optional<PriceStatistics> findFirstByCategoryAndPeriodTypeOrderByCalculatedAtDesc(
            String category, StatPeriod periodType);
}
