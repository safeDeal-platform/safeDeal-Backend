package com.safedeal.domain.notification.repository;

import com.safedeal.domain.notification.entity.Notification;
import com.safedeal.domain.notification.entity.NotificationChannel;
import com.safedeal.domain.notification.entity.NotificationTargetType;
import com.safedeal.domain.notification.entity.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 알림 리포지토리 통합 테스트 — 파생 쿼리(사용자·채널 필터, id DESC 정렬, sinceId 증분,
 * Top10 상한)가 실제 MySQL에서 의도대로 도는지 고정한다.
 *
 * 프로젝트 표준대로 Testcontainers MySQL을 쓴다(H2로 대체하지 않는다 — 방언 차이로 "로컬에선
 * 되는데"를 만들기 때문). local 프로파일이라 스키마는 엔티티대로 create-drop으로 만들어진다.
 * Docker가 필요하며 CI에서 실행된다. 각 테스트는 @Transactional로 롤백돼 서로 격리된다.
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
@Transactional
@Testcontainers
class NotificationRepositoryTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    NotificationRepository notificationRepository;

    private static final Long USER = 42L;
    private static final Long OTHER_USER = 99L;

    private Notification inApp() {
        return Notification.inApp(USER, NotificationType.PRICE_DROP, "제목", "본문",
                NotificationTargetType.LISTING, "01J3ARSNIP", null);
    }

    @Test
    @DisplayName("사용자·IN_APP 채널만, id 내림차순으로 조회한다 (다른 사용자·EMAIL 제외)")
    void findsOnlyOwnInAppOrderedDesc() {
        Notification first = notificationRepository.save(inApp());
        Notification second = notificationRepository.save(inApp());
        // 제외 대상: 다른 사용자 IN_APP, 같은 사용자 EMAIL
        notificationRepository.save(Notification.inApp(OTHER_USER, NotificationType.CHAT, "t", "b", null, null, null));
        Notification email = inApp();
        ReflectionTestUtils.setField(email, "channel", NotificationChannel.EMAIL);
        notificationRepository.save(email);

        List<Notification> result =
                notificationRepository.findTop10ByUserIdAndChannelOrderByIdDesc(USER, NotificationChannel.IN_APP);

        assertThat(result).extracting(Notification::getId)
                .containsExactly(second.getId(), first.getId());
    }

    @Test
    @DisplayName("sinceId 초과분만 조회한다 (증분 폴링)")
    void findsOnlyAfterSinceId() {
        Notification first = notificationRepository.save(inApp());
        Notification second = notificationRepository.save(inApp());

        List<Notification> result = notificationRepository
                .findTop10ByUserIdAndChannelAndIdGreaterThanOrderByIdDesc(
                        USER, NotificationChannel.IN_APP, first.getId());

        assertThat(result).extracting(Notification::getId).containsExactly(second.getId());
    }

    @Test
    @DisplayName("표시 상한 10개를 넘겨 저장해도 최신 10개만 조회된다")
    void capsAtTen() {
        for (int i = 0; i < 12; i++) {
            notificationRepository.save(inApp());
        }

        List<Notification> result =
                notificationRepository.findTop10ByUserIdAndChannelOrderByIdDesc(USER, NotificationChannel.IN_APP);

        assertThat(result).hasSize(10);
    }
}
