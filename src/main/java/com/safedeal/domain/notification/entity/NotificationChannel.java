package com.safedeal.domain.notification.entity;

/**
 * 알림 전달 채널.
 *
 * 정책상 MVP 전달 수단은 {@link #IN_APP}(알림 레코드 + 커서 폴링) 하나다. 채널을 enum으로
 * 추상화해 두는 이유는, 앱 출시 시 FCM(푸시)이나 이메일을 붙일 때 알림 도메인 구조를 바꾸지
 * 않고 채널만 추가하기 위해서다 — 지금은 IN_APP만 실제로 생성/조회된다.
 */
public enum NotificationChannel {

    /** 앱 내 알림 목록. MVP의 유일한 전달 채널. */
    IN_APP,

    /** 이메일. (아직 미사용 — 채널 확장 지점) */
    EMAIL,

    /** Server-Sent Events 실시간 푸시. (MVP 제외 — 폴링이 전달 채널) */
    SSE
}
