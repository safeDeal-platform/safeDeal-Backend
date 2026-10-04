package com.safedeal.domain.notification.entity;

/** 알림 전달 상태. IN_APP은 레코드가 곧 전달이라 생성 즉시 {@link #SENT}다. */
public enum NotificationStatus {

    /** 생성됐으나 아직 전송 전. (외부 채널 전송 대기) */
    PENDING,

    /** 전송 완료 — IN_APP은 생성 즉시 이 상태. */
    SENT,

    /** 전송 실패 (재시도 대상). */
    FAILED
}
