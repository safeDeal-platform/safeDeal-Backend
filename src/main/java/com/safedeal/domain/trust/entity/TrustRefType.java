package com.safedeal.domain.trust.entity;

/**
 * 점수 변동의 근거가 된 대상 종류 (정책 '도메인 연계 계약'의 payload refType).
 *
 * {@code refType}+{@code refId}가 "어느 거래·신고 때문에 점수가 움직였나"를 가리키고,
 * 멱등 키의 일부가 된다.
 *
 * 거래완료와 후기가 둘 다 {@link #ORDER_ITEM}인 것은 의도다 — 2026-08-30에 참조 키를
 * order_item_id로 통일했다. 후기가 transaction_id를 쓰던 시절에는 같은 거래를 두 키로 불러
 * 멱등 축이 갈렸다. (ERD에는 REVIEW 값이 남아 있는데, 통일 결정 이후로 쓰일 경로가 없다 —
 * ERD 쪽을 맞춰야 한다.)
 */
public enum TrustRefType {

    /** 거래완료(TXN-7) · 후기(REV-2) — 둘 다 order_items.id를 가리킨다. */
    ORDER_ITEM,

    /** 제재 확정(RPT-6) — 제재 대상이 된 매물·채팅방 신고를 가리킨다. */
    REPORT
}
