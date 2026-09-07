package com.safedeal.domain.notification.service;

import com.safedeal.domain.notification.entity.Notification;
import com.safedeal.domain.notification.repository.NotificationRepository;
import com.safedeal.global.exception.BusinessException;
import com.safedeal.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 알림 쓰기(Command) 서비스. 조회는 {@link NotificationQueryService}가 담당한다 —
 * 정책상 CQRS를 서비스 계층에서 나눈다.
 *
 * <p>읽음 처리가 존재하는 근거: 정책(재민/알림 — '결정 이력')이 2026-08-30에 "읽음 처리가
 * 있어야 함"으로 확정됐다. 그전 정책은 "1회성이라 읽음 처리 불필요"였고 API 명세서의
 * '알림 읽음 처리'도 그때 폐기 표시가 붙었는데, CLAUDE.md 기준 문서는 노션 정책 페이지다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class NotificationCommandService {

    private final NotificationRepository notificationRepository;

    /**
     * 알림을 읽음으로 표시한다.
     *
     * <p>남의 알림이거나 없는 알림이면 똑같이 404다 — 소유자가 아닐 때 403을 주면 "그 id의
     * 알림은 존재한다"가 새 나간다. 매물 상세가 차단·삭제 건을 404로 돌려주는 것과 같은 기준이다.
     *
     * <p>이미 읽은 알림에 다시 호출해도 성공이고 최초 읽은 시각이 유지된다
     * ({@link Notification#markAsRead}). 폴링 클라이언트가 같은 알림을 두 번 보낼 수 있어서
     * 재요청이 오류가 되면 안 된다.
     */
    public void markAsRead(Long userId, Long notificationId) {
        Notification notification = notificationRepository
                .findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new BusinessException(
                        CommonErrorCode.RESOURCE_NOT_FOUND, "알림을 찾을 수 없습니다."));

        notification.markAsRead(Instant.now());
    }
}
