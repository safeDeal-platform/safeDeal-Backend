package com.safedeal.domain.notification.entity;

/** 알림 딥링크 대상 종류 — 알림을 눌렀을 때 어디로 이동할지 정한다. null이면 이동 없는 단순 안내다. */
public enum NotificationTargetType {

    /** 매물 상세. */
    LISTING,

    /** 검증 결과. */
    VERIFICATION,

    /** 거래 상세. */
    TRADE,

    /** 채팅방. */
    CHAT
}
