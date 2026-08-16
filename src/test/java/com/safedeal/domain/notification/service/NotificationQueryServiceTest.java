package com.safedeal.domain.notification.service;

import com.safedeal.domain.notification.dto.NotificationListResponse;
import com.safedeal.domain.notification.entity.Notification;
import com.safedeal.domain.notification.entity.NotificationChannel;
import com.safedeal.domain.notification.entity.NotificationTargetType;
import com.safedeal.domain.notification.entity.NotificationType;
import com.safedeal.domain.notification.repository.NotificationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 알림 조회 서비스 단위 테스트 — 리포지토리를 목으로 대체해 DB 없이 검증한다.
 * (분기 선택 · IN_APP 채널 고정 · latestId 산출 규칙)
 */
@ExtendWith(MockitoExtension.class)
class NotificationQueryServiceTest {

    @Mock
    NotificationRepository notificationRepository;

    @InjectMocks
    NotificationQueryService notificationQueryService;

    private static final Long USER_ID = 42L;

    /** id·createdAt은 영속화 시 채워지므로, 단위 테스트에서는 리플렉션으로 심어 준다. */
    private static Notification notificationWithId(long id) {
        Notification n = Notification.inApp(
                USER_ID, NotificationType.PRICE_DROP,
                "찜한 매물 가격 인하", "아이폰 15 프로가 90만원으로 내려갔어요",
                NotificationTargetType.LISTING, "01J3ARSNIP", null);
        ReflectionTestUtils.setField(n, "id", id);
        ReflectionTestUtils.setField(n, "createdAt", Instant.parse("2026-08-16T00:00:00Z"));
        return n;
    }

    @Test
    @DisplayName("sinceId가 없으면 최신 10개를 IN_APP 채널로 조회하고 latestId는 가장 큰 id다")
    void getNotifications_withoutSinceId_returnsLatest() {
        // id DESC 정렬 결과(첫 항목이 최신)
        when(notificationRepository.findTop10ByUserIdAndChannelOrderByIdDesc(USER_ID, NotificationChannel.IN_APP))
                .thenReturn(List.of(notificationWithId(88), notificationWithId(87)));

        NotificationListResponse response = notificationQueryService.getNotifications(USER_ID, null);

        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).notificationId()).isEqualTo(88);
        assertThat(response.latestId()).isEqualTo(88);
        verify(notificationRepository)
                .findTop10ByUserIdAndChannelOrderByIdDesc(USER_ID, NotificationChannel.IN_APP);
        verifyNoMoreInteractions(notificationRepository);
    }

    @Test
    @DisplayName("sinceId가 있으면 그 id 초과분만 IN_APP 채널로 조회한다")
    void getNotifications_withSinceId_returnsIncremental() {
        when(notificationRepository.findTop10ByUserIdAndChannelAndIdGreaterThanOrderByIdDesc(
                eq(USER_ID), eq(NotificationChannel.IN_APP), eq(88L)))
                .thenReturn(List.of(notificationWithId(90)));

        NotificationListResponse response = notificationQueryService.getNotifications(USER_ID, 88L);

        assertThat(response.items()).hasSize(1);
        assertThat(response.latestId()).isEqualTo(90);
        verify(notificationRepository).findTop10ByUserIdAndChannelAndIdGreaterThanOrderByIdDesc(
                USER_ID, NotificationChannel.IN_APP, 88L);
        verifyNoMoreInteractions(notificationRepository);
    }

    @Test
    @DisplayName("결과가 비면 items는 빈 목록, latestId는 null이다")
    void getNotifications_empty_returnsNullLatestId() {
        when(notificationRepository.findTop10ByUserIdAndChannelOrderByIdDesc(USER_ID, NotificationChannel.IN_APP))
                .thenReturn(List.of());

        NotificationListResponse response = notificationQueryService.getNotifications(USER_ID, null);

        assertThat(response.items()).isEmpty();
        assertThat(response.latestId()).isNull();
    }
}
