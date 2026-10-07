package com.safedeal.domain.notification.repository;

import com.safedeal.domain.notification.entity.Notification;
import com.safedeal.domain.notification.entity.NotificationChannel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 알림 조회 리포지토리. 조건이 고정된 정적 쿼리라 QueryDSL 없이 파생 쿼리로 충분하다. */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** 최신 알림 최대 10개(첫 진입). id DESC라 첫 항목이 가장 최신(latestId)이다. */
    List<Notification> findTop10ByUserIdAndChannelOrderByIdDesc(Long userId, NotificationChannel channel);

    /**
     * sinceId 초과분을 오래된 것부터 최대 10개(폴링 catch-up). id ASC로 가져와야 밀린
     * 알림이 10개를 넘어도 커서에 구멍이 안 생긴다. 표시 순서(최신순)는 서비스 계층에서 뒤집는다.
     */
    List<Notification> findTop10ByUserIdAndChannelAndIdGreaterThanOrderByIdAsc(
            Long userId, NotificationChannel channel, Long sinceId);

    /** 읽음 처리 대상 조회. id만이 아니라 userId까지 조건에 넣어 남의 알림은 애초에 안 보이게 한다. */
    Optional<Notification> findByIdAndUserId(Long id, Long userId);

    /** 안 읽은 IN_APP 알림 개수(배지). 목록은 최대 10건만 와서 이 COUNT가 따로 필요하다. */
    long countByUserIdAndChannelAndReadAtIsNull(Long userId, NotificationChannel channel);
}
