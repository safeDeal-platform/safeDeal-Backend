package com.safedeal.domain.notification.entity;

/** 알림 전달 채널. MVP는 {@link #IN_APP} 하나뿐이고, 나머지는 나중에 채널만 추가할 수 있게 자리만 둔다. */
public enum NotificationChannel {

    /** 앱 내 알림 목록. MVP의 유일한 전달 채널. */
    IN_APP,

    /** 이메일. (아직 미사용 — 채널 확장 지점) */
    EMAIL,

    /** Server-Sent Events 실시간 푸시. (MVP 제외 — 폴링이 전달 채널) */
    SSE
}
