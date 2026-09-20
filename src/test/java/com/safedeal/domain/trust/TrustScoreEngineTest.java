package com.safedeal.domain.trust;

import com.safedeal.domain.trust.dto.TrustScoreChange;
import com.safedeal.domain.trust.entity.TrustReasonCode;
import com.safedeal.domain.trust.entity.TrustRefType;
import com.safedeal.domain.trust.entity.TrustScoreLog;
import com.safedeal.domain.trust.repository.TrustScoreLogRepository;
import com.safedeal.domain.trust.service.TrustScoreService;
import com.safedeal.domain.user.entity.User;
import com.safedeal.domain.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 신뢰도 점수 엔진 (정책 TRS-2 ~ TRS-6).
 *
 * DB를 띄워서 도는 이유: 이 도메인의 핵심 보증이 <b>멱등</b>인데, 그건 UNIQUE 제약과 조회가
 * 실제로 맞물려야 성립한다. mock으로는 "중복을 걸렀다고 믿는" 것만 확인된다.
 *
 * 여기서 고정하는 것은 정책의 숫자와 규칙이다 - delta 값, 조건부 하한, 하한 복원 조건,
 * 동일 상대 월 3회, 그리고 계정 상태를 건드리지 않는다는 경계(TRS-6).
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
@Testcontainers
class TrustScoreEngineTest {

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

    private static final AtomicLong SEQ = new AtomicLong();

    /** BCrypt 해시 자리를 채우기만 하면 되는 값. 이 테스트는 로그인을 거치지 않는다. */
    private static final String DUMMY_HASH = "$2a$10$0123456789012345678901234567890123456789012345678901";

    @Autowired
    TrustScoreService trustScoreService;

    @Autowired
    TrustScoreLogRepository logRepository;

    @Autowired
    UserRepository userRepository;

    private User newUser() {
        long n = SEQ.incrementAndGet();
        return userRepository.save(User.createLocal("trust" + n + "@test.com", DUMMY_HASH, "신뢰" + n));
    }

    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }

    private List<TrustScoreLog> logsOf(User user) {
        return logRepository.findAll().stream()
                .filter(l -> l.getUserId().equals(user.getId()))
                .toList();
    }

    private TrustScoreChange trade(User user, long orderItemId, Long counterpartyId, String eventId) {
        return new TrustScoreChange(user.getId(), TrustReasonCode.TRADE_COMPLETED,
                TrustRefType.ORDER_ITEM, orderItemId, counterpartyId, eventId);
    }

    private TrustScoreChange sanction(User user, long reportId, String eventId) {
        return new TrustScoreChange(user.getId(), TrustReasonCode.REPORT_CONFIRMED,
                TrustRefType.REPORT, reportId, null, eventId);
    }

    // ---------- 반영 ----------

    @Test
    @DisplayName("거래완료는 +10을 더하고 반영 후 점수를 이력에 남긴다")
    void tradeCompletedAddsTen() {
        User user = newUser();

        trustScoreService.apply(trade(user, 1L, 99L, "evt-t-" + user.getId()));

        assertThat(reload(user).getTrustScore()).isEqualTo(TrustScore.INITIAL + 10);
        List<TrustScoreLog> logs = logsOf(user);
        assertThat(logs).hasSize(1);
        assertThat(logs.getFirst().getDelta()).isEqualTo(10);
        assertThat(logs.getFirst().getScoreAfter()).isEqualTo(TrustScore.INITIAL + 10);
        assertThat(logs.getFirst().getCounterpartyId()).isEqualTo(99L);
    }

    @Test
    @DisplayName("제재 확정은 -300을 적용하고 하한 보호를 해제한다")
    void sanctionReleasesFloor() {
        User user = newUser();

        trustScoreService.apply(sanction(user, 7L, "evt-s-" + user.getId()));

        User after = reload(user);
        // 하한이 풀렸으므로 300 밑으로 내려간다.
        assertThat(after.getTrustScore()).isEqualTo(200);
        assertThat(after.isReportedFlag()).isTrue();
    }

    @Test
    @DisplayName("제재 이력이 없으면 점수가 300 밑으로 내려가지 않는다")
    void protectedUserStopsAtFloor() {
        User user = newUser();
        // 싫어요(-50) 5번이면 산술적으로 250이지만 하한 300에서 멈춰야 한다.
        for (int i = 1; i <= 5; i++) {
            trustScoreService.apply(new TrustScoreChange(user.getId(), TrustReasonCode.REVIEW_DISLIKE,
                    TrustRefType.ORDER_ITEM, (long) i, null, "evt-d-" + user.getId() + "-" + i));
        }

        User after = reload(user);
        assertThat(after.getTrustScore()).isEqualTo(TrustScore.PROTECTED_FLOOR);
        assertThat(after.isReportedFlag()).isFalse();
    }

    // ---------- 멱등 ----------

    @Test
    @DisplayName("같은 eventId가 다시 오면 점수가 두 번 반영되지 않는다")
    void sameEventIdIsIgnored() {
        User user = newUser();
        String eventId = "evt-dup-" + user.getId();

        trustScoreService.apply(trade(user, 11L, 99L, eventId));
        trustScoreService.apply(trade(user, 11L, 99L, eventId));

        assertThat(reload(user).getTrustScore()).isEqualTo(TrustScore.INITIAL + 10);
        assertThat(logsOf(user)).hasSize(1);
    }

    @Test
    @DisplayName("eventId가 달라도 같은 사실이면 반영되지 않는다 (발행 측 재시도 방어)")
    void sameFactWithNewEventIdIsIgnored() {
        User user = newUser();

        trustScoreService.apply(trade(user, 12L, 99L, "evt-a-" + user.getId()));
        trustScoreService.apply(trade(user, 12L, 99L, "evt-b-" + user.getId()));

        assertThat(reload(user).getTrustScore()).isEqualTo(TrustScore.INITIAL + 10);
        assertThat(logsOf(user)).hasSize(1);
    }

    // ---------- 어뷰징 ----------

    @Test
    @DisplayName("동일 상대와의 거래완료는 월 3회까지만 가산되고 초과분은 0점으로 남는다")
    void sameCounterpartyIsCappedAtThree() {
        User user = newUser();
        long counterparty = 12345L;

        for (int i = 1; i <= 5; i++) {
            trustScoreService.apply(trade(user, 100L + i, counterparty, "evt-c-" + user.getId() + "-" + i));
        }

        assertThat(reload(user).getTrustScore()).isEqualTo(TrustScore.INITIAL + 30);

        List<TrustScoreLog> logs = logsOf(user);
        // 초과분도 버리지 않는다 - "왜 안 올랐나"를 이력만으로 설명할 수 있어야 한다.
        assertThat(logs).hasSize(5);
        assertThat(logs.stream().filter(l -> l.getDelta() == 0).count()).isEqualTo(2);
    }

    @Test
    @DisplayName("상대가 다르면 한도에 걸리지 않는다")
    void differentCounterpartiesAreNotCapped() {
        User user = newUser();

        for (int i = 1; i <= 5; i++) {
            trustScoreService.apply(trade(user, 200L + i, 500L + i, "evt-f-" + user.getId() + "-" + i));
        }

        assertThat(reload(user).getTrustScore()).isEqualTo(TrustScore.INITIAL + 50);
    }

    @Test
    @DisplayName("거래완료에 상대가 없으면 거부한다 (계약 위반을 조용히 넘기지 않는다)")
    void tradeWithoutCounterpartyIsRejected() {
        User user = newUser();

        assertThatThrownBy(() ->
                trustScoreService.apply(trade(user, 300L, null, "evt-n-" + user.getId())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("counterpartyId");
    }

    // ---------- 하한 복원 ----------

    @Test
    @DisplayName("제재로 풀린 하한은 이후 가산으로 300에 닿을 때 복원된다")
    void floorIsRestoredAfterRecovery() {
        User user = newUser();
        trustScoreService.apply(sanction(user, 21L, "evt-s1-" + user.getId()));
        assertThat(reload(user).getTrustScore()).isEqualTo(200);

        // 200 -> 300까지 +10을 10번. 상대를 매번 바꿔 어뷰징 한도를 피한다.
        for (int i = 1; i <= 10; i++) {
            trustScoreService.apply(trade(user, 400L + i, 600L + i, "evt-r-" + user.getId() + "-" + i));
        }

        User after = reload(user);
        assertThat(after.getTrustScore()).isEqualTo(TrustScore.PROTECTED_FLOOR);
        assertThat(after.isReportedFlag()).isFalse();
    }

    @Test
    @DisplayName("제재 후에도 점수가 300 이상이면 그것만으로 하한이 복원되지는 않는다")
    void sanctionItselfDoesNotRestoreFloor() {
        User user = newUser();
        // 서로 다른 상대 3명과 거래해 +30을 먼저 쌓는다.
        for (int i = 1; i <= 3; i++) {
            trustScoreService.apply(trade(user, 500L + i, 700L + i, "evt-u-" + user.getId() + "-" + i));
        }
        assertThat(reload(user).getTrustScore()).isEqualTo(530);

        trustScoreService.apply(sanction(user, 22L, "evt-s2-" + user.getId()));

        User after = reload(user);
        // 230으로 내려갔고 보호는 풀린 상태여야 한다. 여기서 복원하면 제재가 하한을 연 의미가 없다.
        assertThat(after.getTrustScore()).isEqualTo(230);
        assertThat(after.isReportedFlag()).isTrue();
    }

    // ---------- 경계 ----------

    @Test
    @DisplayName("신뢰도는 계정 상태를 바꾸지 않는다 (TRS-6)")
    void trustDoesNotChangeAccountStatus() {
        User user = newUser();
        var before = reload(user).getStatus();

        trustScoreService.apply(sanction(user, 23L, "evt-st-" + user.getId()));

        assertThat(reload(user).getStatus()).isEqualTo(before);
    }
}
