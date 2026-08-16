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
 * 시세 조회(Query) 서비스. 중분류 × 기간의 최신 집계를 읽어 응답으로 변환한다.
 *
 * 표본 수가 신뢰 기준({@link #MIN_RELIABLE_SAMPLE_COUNT}) 미만이거나 집계 자체가 없으면
 * "시세 정보 부족"으로 응답한다 — 소수 표본으로 계산한 값을 시세로 오인하지 않도록.
 *
 * (Redis 5분 write-back 병행 캐시는 후속 — 정규 집계 경로가 붙을 때 함께 도입한다.)
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PriceStatisticsQueryService {

    /**
     * 시세로 노출할 최소 표본 수(미만이면 "부족" 처리). 정책상 정확한 임계값이 아직 확정되지
     * 않아 잠정값으로 둔다 — 확정 시 이 상수만 조정한다. (명세서 예시가 표본 1건을 부족으로 봄)
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
            return PriceStatisticsResponse.insufficient(0);
        }
        PriceStatistics stat = latest.get();
        if (stat.getSampleCount() < MIN_RELIABLE_SAMPLE_COUNT) {
            return PriceStatisticsResponse.insufficient(stat.getSampleCount());
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
