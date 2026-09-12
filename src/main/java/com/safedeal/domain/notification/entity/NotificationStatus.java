package com.safedeal.domain.notification.entity;

/**
 * 알림 전달 상태.
 *
 * IN_APP 알림은 레코드가 곧 전달이라 생성과 동시에 {@link #SENT}가 된다. 이메일·푸시처럼
 * 외부 전송이 개입하는 채널을 붙일 때 {@link #PENDING} → {@link #SENT}/{@link #FAILED}
 * 전이와 retry_count가 의미를 갖는다.
 */
public enum NotificationStatus {

    /** 생성됐으나 아직 전송 전. (외부 채널 전송 대기) */
    PENDING,

    /** 전송 완료 — IN_APP은 생성 즉시 이 상태. */
    SENT,

    /** 전송 실패 (재시도 대상). */
    FAILED
}
