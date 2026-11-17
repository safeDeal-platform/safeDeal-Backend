package com.safedeal.global.config;

import com.safedeal.testsupport.IntegrationTestSupport;
import com.safedeal.testsupport.MutableClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 운영 시계 빈(CLK-01, QC-2). 테스트 컨텍스트에는 {@link MutableClock}(@Primary)이 함께 있으므로, 그것을 뺀
 * 나머지가 운영 설정이 만든 빈이다.
 */
class ClockConfigTest extends IntegrationTestSupport {

    @Autowired Map<String, Clock> clocks;
    @Autowired Clock injected;

    @Test
    @DisplayName("CLK-01 운영 설정은 Clock.systemUTC() 빈 하나를 등록한다")
    void productionClockIsSystemUtc() {
        List<Clock> production = clocks.values().stream()
                .filter(clock -> !(clock instanceof MutableClock))
                .toList();

        assertThat(production).containsExactly(Clock.systemUTC());
    }

    @Test
    @DisplayName("CLK-01 QC-3 운영 빈과 함께 있어도 주입되는 시계는 테스트 시계(@Primary)다")
    void testClockWinsInjection() {
        assertThat(injected).isInstanceOf(MutableClock.class);
    }
}
