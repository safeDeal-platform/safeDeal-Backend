package com.safedeal.domain.trust.entity;

/**
 * 점수 변동 사유와 그 delta (정책 TRS-2).
 *
 * <b>delta를 이벤트가 아니라 여기에 두는 이유</b>: 정책이 "delta는 이벤트에 미탑재 —
 * 사유→점수 매핑표는 신뢰도 도메인 소유"로 못 박았다. 발행 측(신고·거래)이 점수를 실어 보내면
 * 점수 정책을 바꿀 때마다 남의 도메인 배포가 필요해지고, 발행 측이 잘못된 값을 보내면 그대로
 * 반영된다. 이벤트는 "무슨 일이 있었는지"만 말하고 "얼마인지"는 여기서 정한다.
 *
 * 값은 내부 1000 척도다({@link com.safedeal.domain.trust.TrustScore}). 표시값은 ÷10이다.
 *
 * <b>전부 잠정이다</b> — 정책이 "모든 delta는 MVP 이후 운영 데이터로 재조정"이라고 명시했다.
 * 거래완료 +1.0만 확정이고 나머지는 값이 바뀔 수 있다.
 */
public enum TrustReasonCode {

    /** 거래완료 (TXN-7). 구매자·판매자 양쪽에 각각 들어온다. 표시 +1.0 — 정책 확정값. */
    TRADE_COMPLETED(+10),

    /**
     * 제재 확정 (RPT-6). 표시 −30.0 — 잠정값.
     *
     * '얻기 어렵고 잃기 쉽게'라는 비대칭이 여기 있다. 거래 30번으로 쌓은 점수가 제재 한 번에
     * 사라진다. 이 사유만 하한 보호를 해제한다(정책 TRS-3).
     */
    REPORT_CONFIRMED(-300),

    /** 후기 좋아요 (REV-2). 표시 +1.0 — 잠정값. */
    REVIEW_LIKE(+10),

    /** 후기 싫어요 (REV-2). 표시 −5.0 — 잠정값. */
    REVIEW_DISLIKE(-50);

    private final int delta;

    TrustReasonCode(int delta) {
        this.delta = delta;
    }

    /** 내부 1000 척도 기준 증감폭. */
    public int delta() {
        return delta;
    }

    /**
     * 어뷰징 한도(동일 상대 월 3회)를 적용받는 사유인지 (정책 TRS-5).
     *
     * 거래완료만 대상이다. 셀프 거래·작업장 계정이 같은 상대와 반복 거래로 점수를 뻥튀기하는
     * 것을 막는 규칙이라, 감점(제재)이나 후기에는 적용할 이유가 없다 — 감점에 한도를 두면
     * 오히려 반복 가해자가 보호받는다.
     */
    public boolean isAbuseCapped() {
        return this == TRADE_COMPLETED;
    }

    /** 조건부 하한 보호를 해제하는 사유인지 (정책 TRS-3). 제재 확정만 해당한다. */
    public boolean releasesFloor() {
        return this == REPORT_CONFIRMED;
    }
}
