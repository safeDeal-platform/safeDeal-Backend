package com.safedeal.domain.notification.service;

import com.safedeal.domain.notification.entity.Notification;
import com.safedeal.domain.notification.entity.NotificationTargetType;
import com.safedeal.domain.notification.entity.NotificationType;
import com.safedeal.domain.notification.repository.NotificationRepository;
import com.safedeal.global.exception.BusinessException;
import com.safedeal.global.exception.CommonErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 알림 쓰기 서비스 단위 테스트 — 리포지토리를 목으로 대체해 DB 없이 검증한다.
 * (소유자 조건이 쿼리에 실리는지 · 없으면 404 · 재호출 멱등)
 */
@ExtendWith(MockitoExtension.class)
class NotificationCommandServiceTest {

    @Mock
    NotificationRepository notificationRepository;

    @InjectMocks
    NotificationCommandService notificationCommandService;

    private static final Long USER_ID = 42L;
    private static final Long NOTIFICATION_ID = 88L;

    private static Notification notificationWithId(long id) {
        Notification n = Notification.inApp(
                USER_ID, NotificationType.PRICE_DROP, "찜한 매물 가격 인하",
                "아이폰 15 프로가 90만원으로 내려갔어요",
                NotificationTargetType.LISTING, "01J3ARSNIP", null);
        ReflectionTestUtils.setField(n, "id", id);
        return n;
    }

    @Test
    @DisplayName("읽음 처리하면 읽은 시각이 채워진다")
    void marksAsRead() {
        Notification notification = notificationWithId(NOTIFICATION_ID);
        when(notificationRepository.findByIdAndUserId(NOTIFICATION_ID, USER_ID))
                .thenReturn(Optional.of(notification));

        notificationCommandService.markAsRead(USER_ID, NOTIFICATION_ID);

        assertThat(notification.isRead()).isTrue();
        assertThat(notification.getReadAt()).isNotNull();
    }

    @Test
    @DisplayName("소유자 조건을 쿼리에 실어 조회한다 — 조회 후 비교가 아니다 (IDOR 방지)")
    void queriesWithOwnerCondition() {
        when(notificationRepository.findByIdAndUserId(NOTIFICATION_ID, USER_ID))
                .thenReturn(Optional.of(notificationWithId(NOTIFICATION_ID)));

        notificationCommandService.markAsRead(USER_ID, NOTIFICATION_ID);

        // findById(id) 뒤에 소유자를 비교하는 구조였다면 이 단언이 깨진다.
        verify(notificationRepository).findByIdAndUserId(NOTIFICATION_ID, USER_ID);
    }

    @Test
    @DisplayName("없거나 남의 알림이면 404다 — 존재 여부를 알려주지 않는다")
    void throwsNotFoundWhenMissingOrNotOwned() {
        when(notificationRepository.findByIdAndUserId(NOTIFICATION_ID, USER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> notificationCommandService.markAsRead(USER_ID, NOTIFICATION_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    @DisplayName("이미 읽은 알림에 다시 호출해도 성공하고 최초 시각이 유지된다")
    void isIdempotent() {
        Notification notification = notificationWithId(NOTIFICATION_ID);
        Instant firstRead = Instant.parse("2026-09-06T10:00:00Z");
        notification.markAsRead(firstRead);
        when(notificationRepository.findByIdAndUserId(NOTIFICATION_ID, USER_ID))
                .thenReturn(Optional.of(notification));

        notificationCommandService.markAsRead(USER_ID, NOTIFICATION_ID);

        assertThat(notification.getReadAt()).isEqualTo(firstRead);
    }
}
