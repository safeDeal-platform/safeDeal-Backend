package com.safedeal.domain.report.service;

import com.safedeal.domain.listing.entity.Listing;
import com.safedeal.domain.report.ReportCreateIntegrationSupport;
import com.safedeal.domain.report.dto.ReportCreateRequest;
import com.safedeal.domain.report.dto.ReportCreateResponse;
import com.safedeal.domain.report.entity.ReportReason;
import com.safedeal.domain.report.entity.ReportStatus;
import com.safedeal.domain.report.exception.ReportErrorCode;
import com.safedeal.domain.report.repository.ReportDailyLimiter;
import com.safedeal.domain.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * 신고 접수의 동시 요청(RPT-C37~C40)을 실제 MySQL·Redis에서 재현한다. 목으로 예외를 던지는 단위
 * 테스트로는 UNIQUE 제약·행 잠금·트랜잭션 경계가 실제로 맞물리는지 증명할 수 없다.
 *
 * <p>하루 한도 카운터는 진짜 빈을 감싼 spy다 — 실제 Redis로 세면서, C37에서만 "두 요청이 모두 중복 판정을
 * 통과한 뒤" 지점에 배리어를 끼워 충돌을 결정적으로 만든다(한도는 중복 판정 뒤·저장 직전에 센다, Q2).
 */
class ReportCreateConcurrencyTest extends ReportCreateIntegrationSupport {

    @Autowired ReportCommandService reportCommandService;
    @MockitoSpyBean ReportDailyLimiter dailyLimiter;

    private User reporter;
    private Listing listing;
    private ReportCreateRequest fraud;

    @BeforeEach
    void setUp() {
        reporter = user();
        listing = activeListing(user());
        fraud = listingRequest(listing, ReportReason.FRAUD, "허위 매물");
    }

    private Callable<?> receive(ReportCreateRequest request) {
        return () -> reportCommandService.receive(reporter.getId(), request);
    }

    private String cancelledReportId() {
        ReportCreateResponse first = reportCommandService.receive(reporter.getId(), fraud);
        reportCommandService.cancel(reporter.getId(), first.reportId());
        return first.reportId();
    }

    private void assertOneSucceededOtherDuplicate(List<Object> outcomes) {
        assertThat(outcomes).filteredOn(o -> o instanceof ReportCreateResponse).hasSize(1);
        assertThat(outcomes).filteredOn(o -> !(o instanceof ReportCreateResponse))
                .singleElement()
                .satisfies(o -> assertThat(errorCodeOf(o)).isEqualTo(ReportErrorCode.DUPLICATE_ACTIVE_REPORT));
    }

    @Test
    @DisplayName("RPT-C37 같은 새 신고 2건이 둘 다 중복 판정을 통과한 뒤 INSERT하면 UNIQUE 충돌이 나고, 진 쪽은 재시도로 409(RPT001)를 받는다")
    void concurrentNewReportsCollideOnUniqueKey() throws Exception {
        // 두 요청이 모두 "기존 신고 없음"을 본 뒤 한도 카운터에서 서로를 기다리게 한다 → 둘 다 INSERT로 가서
        // 반드시 UNIQUE 충돌이 난다. 진 쪽의 재시도는 중복 판정에서 끝나야 하므로 카운터에 다시 오지 않는다
        // (다시 오면 혼자 배리어를 기다리다 시간 초과로 실패한다 — Q2 위반을 드러낸다).
        CyclicBarrier bothPassedDuplicateCheck = new CyclicBarrier(2);
        doAnswer(invocation -> {
            bothPassedDuplicateCheck.await(10, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(dailyLimiter).tryAcquire(any(), any());

        List<Object> outcomes = runConcurrently(List.of(receive(fraud), receive(fraud)));

        assertOneSucceededOtherDuplicate(outcomes);
        assertThat(reportRepository.findAll()).hasSize(1);
    }

    /**
     * 부활 경로는 잠금 재조회가 카운터보다 앞이라, C37처럼 카운터에 배리어를 끼우면 잠금을 쥔 쪽이 기다리는 동안
     * 다른 쪽이 잠금에서 막혀 서로 영영 만나지 못한다. 그래서 동시 출발만 맞추고 여러 번 돌린다.
     * <b>한계:</b> 두 요청이 우연히 직렬로 처리되면 잠금 경합 없이도 같은 결과(201 + RPT001)가 나므로, 이 테스트는
     * "경합해도 결과가 맞다"를 보장할 뿐 매 회차 경합이 일어났음을 증명하지는 않는다.
     */
    @RepeatedTest(5)
    @DisplayName("RPT-C38 취소된 신고를 동시에 2번 되살리면 하나만 201, 다른 하나는 409(RPT001)이고 RECEIVED 1행이다")
    void concurrentRevivals() throws Exception {
        String reportId = cancelledReportId();

        List<Object> outcomes = runConcurrently(List.of(receive(fraud), receive(fraud)));

        assertOneSucceededOtherDuplicate(outcomes);
        assertThat(outcomes).filteredOn(o -> o instanceof ReportCreateResponse).singleElement()
                .satisfies(o -> assertThat(((ReportCreateResponse) o).reportId()).isEqualTo(reportId));
        assertThat(reportRepository.findAll()).singleElement()
                .satisfies(r -> assertThat(r.getStatus()).isEqualTo(ReportStatus.RECEIVED));
    }

    /**
     * 어느 쪽이 먼저 잠금을 잡을지 정할 수 없어 최종 상태를 하나로 고정하지 않는다. 검증하는 것은
     * 두 요청이 모두 예외 없이 끝나는 것(= 500 없음)과 최종 상태가 둘 중 하나로 일관된 것뿐이다.
     */
    @Test
    @DisplayName("RPT-C39 부활과 취소가 동시에 와도 500 없이 둘 다 끝나고 최종 상태는 RECEIVED 또는 CANCELLED 하나다 (500 없음과 최종 상태 일관성만 검증)")
    void revivalAndCancelEndWithoutErrorAndConsistentState() throws Exception {
        String reportId = cancelledReportId();
        Callable<?> cancel = () -> {
            reportCommandService.cancel(reporter.getId(), reportId);
            return "cancelled";
        };

        List<Object> outcomes = runConcurrently(List.of(receive(fraud), cancel));

        assertThat(outcomes.get(0)).isInstanceOf(ReportCreateResponse.class);
        assertThat(outcomes.get(1)).isEqualTo("cancelled");
        assertThat(reportRepository.findAll()).singleElement()
                .satisfies(r -> assertThat(r.getStatus()).isIn(ReportStatus.RECEIVED, ReportStatus.CANCELLED));
    }

    @Test
    @DisplayName("RPT-C40 오늘 10건을 이미 쓴 신고자의 11·12번째 신고가 동시에 오면 둘 다 429(RPT003)이고 저장되지 않는다")
    void eleventhAndTwelfthConcurrentlyAreBothRejected() throws Exception {
        // 서비스가 Instant.now()로 세므로 미리 채우는 쪽도 지금 시각을 쓴다. 한국 자정(UTC 15:00) 바로 앞에서
        // 돌면 채운 날짜와 서비스가 세는 날짜가 갈라져 실패할 수 있다 — 시계를 주입받지 않는 한 남는 한계다.
        for (int i = 0; i < 10; i++) {
            assertThat(dailyLimiter.tryAcquire(reporter.getId(), Instant.now())).isTrue();
        }

        List<Object> outcomes = runConcurrently(List.of(
                receive(fraud), receive(listingRequest(listing, ReportReason.SPAM, null))));

        assertThat(outcomes).allSatisfy(o ->
                assertThat(errorCodeOf(o)).isEqualTo(ReportErrorCode.DAILY_REPORT_LIMIT_EXCEEDED));
        assertThat(reportRepository.findAll()).isEmpty();
    }
}
