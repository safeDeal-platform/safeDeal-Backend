package com.safedeal.domain.trust.entity;

/**
 * 점수 변동 사유와 그 delta (정책 TRS-2). delta를 이벤트가 아니라 여기 두는 이유: 발행 측이
 * 점수를 실어 보내면 정책을 바꿀 때마다 남의 도메인 배포가 필요해지고 잘못된 값도 그대로
 * 반영된다 — 이벤트는 "무슨 일이 있었는지"만 말하고 "얼마인지"는 여기서 정한다.
 *
 * 값은 내부 1000 척도다({@link com.safedeal.domain.trust.TrustScore}). 모든 delta는 MVP
 * 이후 운영 데이터로 재조정하는데, 거래완료 +1.0과 후기 +0.3/−0.5(2026-09-20)는 확정이고
 * 제재 −30.0만 잠정이다.
 */
public enum TrustReasonCode {

    /** 거래완료 (TXN-7). 구매자·판매자 양쪽에 각각 들어온다. 표시 +1.0 — 정책 확정값. */
    TRADE_COMPLETED(+10),

    /**
     * 제재 확정 (RPT-6). 표시 −30.0 — 잠정값. 거래 30번 쌓은 점수가 한 번에 사라지는 비대칭이고,
     * 이 사유만 하한 보호를 해제한다(정책 TRS-3).
     */
    REPORT_CONFIRMED(-300),

    /** 후기 좋아요 (REV-2). 표시 +0.3 — 정책 확정값(2026-09-20). */
    REVIEW_LIKE(+3),

    /** 후기 싫어요 (REV-2). 표시 −0.5 — 정책 확정값(2026-09-20). */
    REVIEW_DISLIKE(-5);

    private final int delta;

    TrustReasonCode(int delta) {
        this.delta = delta;
    }

    /** 내부 1000 척도 기준 증감폭. */
    public int delta() {
        return delta;
    }

    /**
     * 어뷰징 한도(동일 상대 월 3회)를 적용받는 사유인지 (정책 TRS-5). 지금은 거래완료만
     * 대상이다 — 셀프 거래·반복 거래로 점수를 뻥튀기하는 것을 막는 규칙이라, 감점(제재)에
     * 한도를 두면 오히려 반복 가해자가 보호받는다.
     *
     * <p>정책(2026-09-20)은 후기 가산에도 같은 한도를 적용하기로 했지만 아직 코드에 없다 —
     * 후기 가산의 상대 판정 기준을 정한 뒤 여기와 {@code TrustScoreService}에 반영할 후속
     * 작업이다.
     */
    public boolean isAbuseCapped() {
        return this == TRADE_COMPLETED;
    }

    /** 조건부 하한 보호를 해제하는 사유인지 (정책 TRS-3). 제재 확정만 해당한다. */
    public boolean releasesFloor() {
        return this == REPORT_CONFIRMED;
    }
}
