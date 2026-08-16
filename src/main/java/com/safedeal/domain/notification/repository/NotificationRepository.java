package com.safedeal.domain.notification.repository;

import com.safedeal.domain.notification.entity.Notification;
import com.safedeal.domain.notification.entity.NotificationChannel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 알림 조회 리포지토리.
 *
 * 목록/폴링 쿼리는 조건이 고정된 정적 쿼리라 QueryDSL 없이 파생 쿼리로 충분하다
 * (데이터 접근 전략: 동적=QueryDSL, 정적=JPA). id DESC + Top10으로 "표시 최대 10개",
 * id 커서(sinceId) 증분을 함께 만족한다.
 */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /**
     * 최신 알림 최대 10개 (첫 진입 — sinceId 없음).
     * id DESC 정렬이라 결과의 첫 항목이 가장 최신(= latestId)이다.
     */
    List<Notification> findTop10ByUserIdAndChannelOrderByIdDesc(Long userId, NotificationChannel channel);

    /**
     * sinceId 초과분만 최대 10개 (폴링 catch-up). 클라이언트는 이 결과를 기존 목록과
     * 알림 id로 중복 제거한다.
     */
    List<Notification> findTop10ByUserIdAndChannelAndIdGreaterThanOrderByIdDesc(
            Long userId, NotificationChannel channel, Long sinceId);
}
