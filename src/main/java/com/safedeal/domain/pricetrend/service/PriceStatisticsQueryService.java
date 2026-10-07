package com.safedeal.domain.pricetrend.service;

import com.safedeal.domain.pricetrend.dto.PriceStatisticsResponse;
import com.safedeal.domain.pricetrend.entity.PriceStatistics;
import com.safedeal.domain.pricetrend.entity.StatPeriod;
import com.safedeal.domain.pricetrend.repository.PriceStatisticsRepository;
import com.safedeal.global.exception.BusinessException;
import com.safedeal.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 시세 조회(Query) 서비스. 표본 수가 {@link #MIN_RELIABLE_SAMPLE_COUNT} 미만이거나 집계가
 * 없으면 "시세 정보 부족"으로 응답한다 — 소수 표본을 시세로 오인하지 않도록.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PriceStatisticsQueryService {

    /**
     * 시세로 노출할 최소 표본 수. 정책상 정확한 임계값이 아직 확정되지 않은 잠정값이다
     * (명세서 예시는 표본 1건을 부족으로 봄).
     */
    static final int MIN_RELIABLE_SAMPLE_COUNT = 5;

    private final PriceStatisticsRepository priceStatisticsRepository;

    /**
     * @param categoryCode 중분류 code(필수)
     * @param periodCode   "7d" 또는 "30d"
     * @throws BusinessException 지원하지 않는 기간 code면 400(INVALID_INPUT)
     */
    public PriceStatisticsResponse getPriceStatistics(String categoryCode, String periodCode) {
        StatPeriod periodType = parsePeriod(periodCode);

        Optional<PriceStatistics> latest = priceStatisticsRepository
                .findFirstByCategoryAndPeriodTypeOrderByCalculatedAtDesc(categoryCode, periodType);

        if (latest.isEmpty()) {
            return PriceStatisticsResponse.insufficient(categoryCode, periodCode, 0);
        }
        PriceStatistics stat = latest.get();
        if (stat.getSampleCount() < MIN_RELIABLE_SAMPLE_COUNT) {
            return PriceStatisticsResponse.insufficient(categoryCode, periodCode, stat.getSampleCount());
        }
        return PriceStatisticsResponse.of(stat, categoryCode, periodCode);
    }

    private StatPeriod parsePeriod(String periodCode) {
        try {
            return StatPeriod.fromCode(periodCode);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, e.getMessage());
        }
    }
}
