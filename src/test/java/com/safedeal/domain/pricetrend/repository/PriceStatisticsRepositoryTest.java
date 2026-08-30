package com.safedeal.domain.pricetrend.repository;

import com.safedeal.domain.pricetrend.entity.PriceStatistics;
import com.safedeal.domain.pricetrend.entity.StatPeriod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 가격 통계 리포지토리 통합 테스트 — "중분류 × 기간의 최신 집계 1건" 파생 쿼리가 실제 MySQL에서
 * calculated_at 최신순·기간종류·중분류 필터를 지키는지 고정한다.
 *
 * 프로젝트 표준대로 Testcontainers MySQL을 쓴다(H2 대체 금지). local 프로파일이라 스키마는
 * 엔티티대로 create-drop. Docker 필요 — CI에서 실행. 각 테스트는 @Transactional로 롤백된다.
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
@Transactional
@Testcontainers
class PriceStatisticsRepositoryTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    PriceStatisticsRepository priceStatisticsRepository;

    private static final String CATEGORY = "DIGITAL_MOBILE";

    private PriceStatistics stat(StatPeriod period, LocalDate start, LocalDate end, Instant calculatedAt) {
        return PriceStatistics.builder()
                .category(CATEGORY)
                .periodType(period)
                .periodStart(start)
                .periodEnd(end)
                .sampleCount(42)
                .avgPrice(920000)
                .medianPrice(900000)
                .minPrice(700000)
                .maxPrice(1200000)
                .stdDev(new BigDecimal("12345.67"))
                .calculatedAt(calculatedAt)
                .build();
    }

    @Test
    @DisplayName("같은 중분류·기간이면 calculated_at이 가장 최신인 집계를 돌려준다")
    void returnsLatestByCalculatedAt() {
        priceStatisticsRepository.save(stat(StatPeriod.D7,
                LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 9), Instant.parse("2026-08-09T00:00:00Z")));
        PriceStatistics newer = priceStatisticsRepository.save(stat(StatPeriod.D7,
                LocalDate.of(2026, 8, 9), LocalDate.of(2026, 8, 16), Instant.parse("2026-08-16T00:00:00Z")));

        Optional<PriceStatistics> result = priceStatisticsRepository
                .findFirstByCategoryAndPeriodTypeOrderByCalculatedAtDesc(CATEGORY, StatPeriod.D7);

        assertThat(result).get().extracting(PriceStatistics::getId).isEqualTo(newer.getId());
    }

    @Test
    @DisplayName("기간 종류(7d/30d)로 구분해 조회한다")
    void filtersByPeriodType() {
        priceStatisticsRepository.save(stat(StatPeriod.D7,
                LocalDate.of(2026, 8, 9), LocalDate.of(2026, 8, 16), Instant.parse("2026-08-16T00:00:00Z")));
        PriceStatistics d30 = priceStatisticsRepository.save(stat(StatPeriod.D30,
                LocalDate.of(2026, 7, 17), LocalDate.of(2026, 8, 16), Instant.parse("2026-08-16T00:00:00Z")));

        Optional<PriceStatistics> result = priceStatisticsRepository
                .findFirstByCategoryAndPeriodTypeOrderByCalculatedAtDesc(CATEGORY, StatPeriod.D30);

        assertThat(result).get().extracting(PriceStatistics::getId).isEqualTo(d30.getId());
    }

    @Test
    @DisplayName("집계가 없는 중분류는 빈 결과다")
    void emptyForUnknownCategory() {
        Optional<PriceStatistics> result = priceStatisticsRepository
                .findFirstByCategoryAndPeriodTypeOrderByCalculatedAtDesc("UNKNOWN", StatPeriod.D7);

        assertThat(result).isEmpty();
    }
}
