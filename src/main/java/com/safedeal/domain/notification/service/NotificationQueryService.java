package com.safedeal.domain.notification.service;

import com.safedeal.domain.notification.dto.NotificationListResponse;
import com.safedeal.domain.notification.entity.Notification;
import com.safedeal.domain.notification.entity.NotificationChannel;
import com.safedeal.domain.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 알림 조회(Query) 서비스. 정책상 CQRS를 서비스 계층에서 나눈다 — 조회 전용이라
 * 상태를 바꾸지 않는다(읽기 트랜잭션).
 *
 * MVP 전달 채널은 IN_APP뿐이라 목록은 IN_APP 채널만 조회한다(이메일·SSE 레코드가 생기더라도
 * 앱 내 목록에는 섞이지 않는다).
 */
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
        List<Notification> notifications = (sinceId == null)
                ? notificationRepository.findTop10ByUserIdAndChannelOrderByIdDesc(
                        userId, NotificationChannel.IN_APP)
                : notificationRepository.findTop10ByUserIdAndChannelAndIdGreaterThanOrderByIdDesc(
                        userId, NotificationChannel.IN_APP, sinceId);
        return NotificationListResponse.of(notifications);
    }
}
