package com.safedeal.domain.notification.service;

import com.safedeal.domain.notification.entity.Notification;
import com.safedeal.domain.notification.repository.NotificationRepository;
import com.safedeal.global.exception.BusinessException;
import com.safedeal.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** 알림 쓰기(Command) 서비스. 조회는 {@link NotificationQueryService}가 담당한다. */
@Service
@RequiredArgsConstructor
@Transactional
public class NotificationCommandService {

    private final NotificationRepository notificationRepository;

    /**
     * 알림을 읽음으로 표시한다. 남의 알림이거나 없는 알림이면 똑같이 404다 — 403을 주면
     * "그 id의 알림이 존재한다"는 사실이 새 나간다.
     *
     * <p>이미 읽은 알림에 다시 호출해도 성공이고 최초 읽은 시각이 유지된다.
     */
    public void markAsRead(Long userId, Long notificationId) {
        Notification notification = notificationRepository
                .findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new BusinessException(
                        CommonErrorCode.RESOURCE_NOT_FOUND, "알림을 찾을 수 없습니다."));

        notification.markAsRead(Instant.now());
    }
}
