package com.safedeal.domain.notification.service;

import com.safedeal.domain.notification.dto.NotificationListResponse;
import com.safedeal.domain.notification.dto.UnreadCountResponse;
import com.safedeal.domain.notification.entity.Notification;
import com.safedeal.domain.notification.entity.NotificationChannel;
import com.safedeal.domain.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 알림 조회(Query) 서비스 — 상태를 바꾸지 않는다. 목록은 IN_APP 채널만 본다(MVP 전달 채널이 그것뿐). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationQueryService {

    private final NotificationRepository notificationRepository;

    /**
     * 사용자의 최신 IN_APP 알림 목록(최대 10개)을 조회한다.
     *
     * @param userId  현재 사용자 내부 PK
     * @param sinceId 이 id 초과분만 조회(폴링 catch-up). null이면 최신 10개.
     */
    public NotificationListResponse getNotifications(Long userId, Long sinceId) {
        if (sinceId == null) {
            List<Notification> notifications = notificationRepository
                    .findTop10ByUserIdAndChannelOrderByIdDesc(userId, NotificationChannel.IN_APP);
            return NotificationListResponse.of(notifications);
        }

        // catch-up은 오래된 것부터(id ASC) 소진해야 커서에 구멍이 안 생긴다. 표시는 최신순이라 여기서 뒤집는다.
        List<Notification> oldestUnseenFirst = notificationRepository
                .findTop10ByUserIdAndChannelAndIdGreaterThanOrderByIdAsc(
                        userId, NotificationChannel.IN_APP, sinceId);
        List<Notification> newestFirst = new ArrayList<>(oldestUnseenFirst);
        Collections.reverse(newestFirst);
        return NotificationListResponse.of(newestFirst);
    }

    /**
     * 안 읽은 IN_APP 알림 개수(배지) — 화면에 안 보이는 오래된 안읽음도 전부 센다.
     *
     * @param userId 현재 사용자 내부 PK
     */
    public UnreadCountResponse getUnreadCount(Long userId) {
        long unreadCount = notificationRepository
                .countByUserIdAndChannelAndReadAtIsNull(userId, NotificationChannel.IN_APP);
        return new UnreadCountResponse(unreadCount);
    }
}
