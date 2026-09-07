package com.safedeal.domain.notification.repository;

import com.safedeal.domain.notification.entity.Notification;
import com.safedeal.domain.notification.entity.NotificationChannel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 알림 조회 리포지토리.
 *
 * 목록/폴링 쿼리는 조건이 고정된 정적 쿼리라 QueryDSL 없이 파생 쿼리로 충분하다
 * (데이터 접근 전략: 동적=QueryDSL, 정적=JPA). 첫 진입은 id DESC + Top10으로 "표시 최대
 * 10개"를, 증분(catch-up)은 id ASC + Top10으로 sinceId 이후 구간을 순서대로 소진한다.
 */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /**
     * 최신 알림 최대 10개 (첫 진입 — sinceId 없음).
     * id DESC 정렬이라 결과의 첫 항목이 가장 최신(= latestId)이다.
     */
    List<Notification> findTop10ByUserIdAndChannelOrderByIdDesc(Long userId, NotificationChannel channel);

    /**
     * sinceId 초과분 중 가장 오래된 것부터 최대 10개 (폴링 catch-up).
     *
     * <p>id ASC로 가져와야 한다 — DESC로 최신 10개를 집으면, 밀린 알림이 10개를 넘을 때
     * 중간 구간이 다음 sinceId(=이번 응답 최대 id)보다 작아 영영 재조회되지 않는다(gap).
     * 오래된 것부터 순서대로 소진해야 커서가 구멍 없이 전진한다. 표시 순서(최신순)는
     * 서비스 계층에서 뒤집는다.
     */
    List<Notification> findTop10ByUserIdAndChannelAndIdGreaterThanOrderByIdAsc(
            Long userId, NotificationChannel channel, Long sinceId);

    /**
     * 읽음 처리 대상 조회.
     *
     * <p><b>id만이 아니라 userId까지 조건에 넣는다.</b> id만으로 찾아 온 뒤 소유자를 비교하면
     * 검사를 빠뜨리는 순간 남의 알림을 읽음 처리할 수 있다(IDOR). 조건을 쿼리에 박아두면
     * 남의 알림은 애초에 조회되지 않고, 호출부는 "없으면 404" 하나만 처리하면 된다 —
     * 존재 여부 자체도 알려주지 않는다.
     */
    Optional<Notification> findByIdAndUserId(Long id, Long userId);
}
