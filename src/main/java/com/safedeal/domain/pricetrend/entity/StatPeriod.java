package com.safedeal.domain.pricetrend.entity;

import java.util.Arrays;

/**
 * 시세 통계 집계 기간(7일/30일). DB에는 enum 이름(D7/D30)으로 저장하고, API는
 * {@link #getCode()}("7d"/"30d")로 주고받는다.
 */
public enum StatPeriod {

    D7("7d"),
    D30("30d");

    private final String code;

    StatPeriod(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /**
     * API 코드("7d"/"30d")를 enum으로 변환한다. 지원하지 않는 값이면 예외를 던져
     * 컨트롤러/서비스가 400으로 변환한다.
     */
    public static StatPeriod fromCode(String code) {
        return Arrays.stream(values())
                .filter(p -> p.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("지원하지 않는 기간입니다: " + code));
    }
}
