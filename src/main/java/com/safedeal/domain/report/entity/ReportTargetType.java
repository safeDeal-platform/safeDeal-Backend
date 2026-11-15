package com.safedeal.domain.report.entity;

/**
 * 신고 대상의 종류. 이름은 알림의 {@code NotificationTargetType}(LISTING, CHAT)과 맞춘다.
 * 저장은 {@code EnumType.STRING}으로 한다 — 순서 번호로 저장하면 상수 순서가 바뀔 때 기존 행의 뜻이 달라진다.
 */
public enum ReportTargetType {
    LISTING,
    CHAT
}
