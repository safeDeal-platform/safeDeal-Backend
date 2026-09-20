package com.safedeal.domain.trust;

import java.math.BigDecimal;

/**
 * 신뢰도 점수 척도 (정책 TRS-1).
 *
 * <b>내부는 0~1000 정수, 표시는 그 값을 10으로 나눈 소수 1자리</b>다. 정수로 저장하는 이유는
 * 부동소수 반올림 오차 없이 delta를 더하기 위해서고, 표시를 소수로 두는 이유는 +1.0 같은 작은
 * 변화도 눈에 보이게 하기 위해서다.
 *
 * 이 척도의 소유는 신뢰도 도메인이다. 다른 도메인(예: 인증의 가입 응답)이 점수를 표시할 때는
 * 각자 변환하지 말고 {@link #display(int)}를 쓴다 — 변환식이 흩어지면 나중에 척도를 바꿀 때
 * 한 곳이 빠진 채로 남는다.
 */
public final class TrustScore {

    /** 내부 하한. 조건부 하한 보호가 풀린 계정만 여기까지 내려간다(정책 TRS-3). */
    public static final int MIN = 0;

    /** 내부 상한. 표시 100.0 (정책이 도달 가능하다고 명시). */
    public static final int MAX = 1000;

    /** 가입 시 시작값. 표시 50.0 */
    public static final int INITIAL = 500;

    /**
     * 조건부 하한 (정책 TRS-3). 제재 확정 이력이 없는 계정은 이 밑으로 떨어지지 않는다.
     *
     * 오탐이나 일시적 부진으로 정상 유저가 위험 구간(표시 0.0~29.9)에 잘못 분류되지 않게 하는
     * 보호 장치다. 위험 구간은 제재가 확정된 계정만 진입한다.
     */
    public static final int PROTECTED_FLOOR = 300;

    private TrustScore() {
    }

    /**
     * 표시값으로 변환한다. 내부 500 → 50.0
     *
     * scale 1의 {@link BigDecimal}을 쓰는 이유: double이면 표시 자릿수가 값에 따라 흔들려
     * (50.0이 50으로) 프론트가 다시 포맷해야 한다.
     */
    public static BigDecimal display(int internalScore) {
        return BigDecimal.valueOf(internalScore, 1);
    }

    /**
     * 하한·상한을 적용한다.
     *
     * @param floorReleased 제재 확정으로 하한 보호가 풀렸는지 (users.reported_flag)
     */
    public static int clamp(int rawScore, boolean floorReleased) {
        int floor = floorReleased ? MIN : PROTECTED_FLOOR;
        return Math.max(floor, Math.min(MAX, rawScore));
    }
}
