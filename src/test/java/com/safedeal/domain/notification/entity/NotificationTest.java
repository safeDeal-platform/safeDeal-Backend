package com.safedeal.domain.notification.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 알림 엔티티 단위 테스트 — 읽음 처리 규칙을 DB 없이 고정한다.
 *
 * 읽음 처리가 존재하는 근거는 정책 확정(2026-08-30, 재민/알림 '결정 이력')이다.
 * 그전 정책은 "1회성이라 읽음 처리 불필요"였다.
 */
class NotificationTest {

    private static final Instant FIRST_READ = Instant.parse("2026-09-06T10:00:00Z");
    private static final Instant SECOND_READ = Instant.parse("2026-09-06T11:00:00Z");

    private static Notification inApp() {
        return Notification.inApp(42L, NotificationType.PRICE_DROP, "제목", "본문",
                NotificationTargetType.LISTING, "01J3ARSNIP", null);
    }

    @Test
    @DisplayName("생성 직후에는 읽지 않은 상태다")
    void isUnreadWhenCreated() {
        Notification notification = inApp();

        assertThat(notification.getReadAt()).isNull();
        assertThat(notification.isRead()).isFalse();
    }

    @Test
    @DisplayName("읽음으로 표시하면 읽은 시각이 기록된다")
    void marksAsRead() {
        Notification notification = inApp();

        notification.markAsRead(FIRST_READ);

        assertThat(notification.getReadAt()).isEqualTo(FIRST_READ);
        assertThat(notification.isRead()).isTrue();
    }

    @Test
    @DisplayName("이미 읽은 알림을 다시 읽어도 최초 시각이 유지된다 (멱등)")
    void keepsFirstReadAtOnRepeatedCalls() {
        Notification notification = inApp();
        notification.markAsRead(FIRST_READ);

        notification.markAsRead(SECOND_READ);

        // 폴링 클라이언트는 즉시 push와 catch-up이 겹치는 구간에서 같은 알림을 두 번
        // 읽음 처리로 보낼 수 있다. 그때마다 덮으면 "언제 처음 확인했나"를 잃는다.
        assertThat(notification.getReadAt()).isEqualTo(FIRST_READ);
    }
}
