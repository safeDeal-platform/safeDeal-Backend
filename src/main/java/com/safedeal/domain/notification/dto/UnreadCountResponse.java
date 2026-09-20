package com.safedeal.domain.notification.dto;

/**
 * 안 읽은 알림 개수(배지). 서버는 상한 없이 실제 값을 그대로 내려준다 — "99+" 같은 표기는
 * 화면 몫이고, 서버가 한번 잘라버리면 클라이언트가 원래 값을 복구할 수 없다.
 *
 * @param unreadCount 현재 사용자의 안 읽은 IN_APP 알림 개수
 */
public record UnreadCountResponse(long unreadCount) {
}
