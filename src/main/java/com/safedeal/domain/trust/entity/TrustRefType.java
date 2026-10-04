package com.safedeal.domain.trust.entity;

/**
 * 점수 변동의 근거가 된 대상 종류 (정책 '도메인 연계 계약'의 payload refType).
 * {@code refType}+{@code refId}가 멱등 키의 일부가 된다.
 *
 * 거래완료와 후기가 둘 다 {@link #ORDER_ITEM}인 것은 의도다 — 2026-08-30에 참조 키를
 * order_item_id로 통일해 멱등 축이 갈리지 않게 했다(ERD에는 REVIEW 값이 남아 있으나
 * 쓰이지 않는다).
 */
public enum TrustRefType {

    /** 거래완료(TXN-7) · 후기(REV-2) — 둘 다 order_items.id를 가리킨다. */
    ORDER_ITEM,

    /** 제재 확정(RPT-6) — 제재 대상이 된 매물·채팅방 신고를 가리킨다. */
    REPORT
}
