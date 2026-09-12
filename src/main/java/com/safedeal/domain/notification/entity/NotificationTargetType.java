package com.safedeal.domain.notification.entity;

/**
 * 알림 딥링크 대상 종류. 클라이언트가 알림을 눌렀을 때 어디로 이동할지 결정한다.
 *
 * {@code targetType}이 {@code null}이면 이동 대상이 없는 단순 안내 알림이다.
 * 실제 대상 식별자는 {@link Notification#getTargetId()}(공개 참조 문자열)에 담긴다.
 */
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
