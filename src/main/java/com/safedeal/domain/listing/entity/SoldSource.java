package com.safedeal.domain.listing.entity;

/**
 * SOLD로 들어온 경로. 되돌리기 규칙과 통계 반영 여부가 다르다 — 결제 건은 환불 절차로만
 * 풀리고 통계에 쓰이며, 수동 건은 24시간 내 되돌릴 수 있고 통계에서 제외한다.
 */
public enum SoldSource {
    /** 결제 완료 이벤트로 전이. 통계에 반영된다. */
    PAYMENT,
    /** 판매자가 직접 눌러 전이. 통계에 반영하지 않는다(플랫폼 밖 직거래). */
    MANUAL
}
