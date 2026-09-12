package com.safedeal.domain.trust;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 점수 척도와 하한 규칙 (정책 TRS-1 · TRS-3).
 *
 * 표시 변환은 인증 응답(AuthResponse)도 쓰는 공용 계약이라 여기서 고정한다 —
 * scale이 흔들리면 프론트가 "50.0"과 "50"을 번갈아 받는다.
 */
class TrustScoreTest {

    @Test
    @DisplayName("표시값은 내부값을 10으로 나눈 소수 1자리다")
    void displayKeepsOneDecimal() {
        assertThat(TrustScore.display(500)).isEqualTo(new BigDecimal("50.0"));
        assertThat(TrustScore.display(1000)).isEqualTo(new BigDecimal("100.0"));
        assertThat(TrustScore.display(0)).isEqualTo(new BigDecimal("0.0"));
        // 소수점이 사라지면 프론트가 다시 포맷해야 한다 - scale 자체를 고정한다.
        assertThat(TrustScore.display(500).scale()).isEqualTo(1);
    }

    @Test
    @DisplayName("가입 시작값은 표시 50.0이다")
    void initialScore() {
        assertThat(TrustScore.INITIAL).isEqualTo(500);
        assertThat(TrustScore.display(TrustScore.INITIAL)).isEqualTo(new BigDecimal("50.0"));
    }

    @ParameterizedTest(name = "raw={0} 보호해제={1} -> {2}")
    @DisplayName("하한은 보호 상태에 따라 300 또는 0이고, 상한은 항상 1000이다")
    @CsvSource({
            // 보호 중인 계정은 300 밑으로 안 내려간다
            "200,  false, 300",
            "-500, false, 300",
            "299,  false, 300",
            // 제재로 보호가 풀리면 0까지 내려간다
            "200,  true,  200",
            "-500, true,  0",
            // 상한은 보호 여부와 무관
            "1200, false, 1000",
            "1200, true,  1000",
            // 범위 안이면 그대로
            "500,  false, 500",
    })
    void clampAppliesFloorAndCeiling(int raw, boolean floorReleased, int expected) {
        assertThat(TrustScore.clamp(raw, floorReleased)).isEqualTo(expected);
    }
}
