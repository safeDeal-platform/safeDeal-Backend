package com.safedeal.domain.report.service;

import com.safedeal.domain.report.entity.Report;
import com.safedeal.domain.report.entity.ReportReason;
import com.safedeal.domain.report.entity.ReportStatus;
import com.safedeal.domain.report.entity.ReportTargetType;
import com.safedeal.domain.report.repository.ReportRepository;
import com.safedeal.global.exception.BusinessException;
import com.safedeal.global.exception.CommonErrorCode;
import com.safedeal.testsupport.MutableClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportCommandServiceTest {

    @Mock ReportRepository reportRepository;
    @Spy MutableClock clock = new MutableClock();

    @InjectMocks ReportCommandService reportCommandService;

    private static final String PUBLIC_ID = "01J3ARSNIPREPORT0000000001";
    private static final Long REPORTER = 20L;

    private Report receivedReport() {
        Report report = Report.receive(PUBLIC_ID, REPORTER, ReportTargetType.LISTING, 10L,
                "01J3ARSNIPLISTING000001", 30L, ReportReason.FRAUD, "허위 매물입니다", null,
                Instant.parse("2026-10-07T00:00:00Z"));
        ReflectionTestUtils.setField(report, "id", 99L);
        return report;
    }

    @Test
    @DisplayName("본인의 접수 상태 신고는 취소된다 — 조회로 얻은 내부 id로 조건부 UPDATE를 부른다")
    void cancelsOwnReceivedReport() {
        when(reportRepository.findByPublicIdAndReporterId(PUBLIC_ID, REPORTER))
                .thenReturn(Optional.of(receivedReport()));
        when(reportRepository.cancelByReporter(eq(99L), eq(REPORTER), any(Instant.class))).thenReturn(1);

        reportCommandService.cancel(REPORTER, PUBLIC_ID);

        verify(reportRepository).cancelByReporter(eq(99L), eq(REPORTER), any(Instant.class));
    }

    @Test
    @DisplayName("CLK-04 취소 시각(cancelledAt)은 주입된 시계의 값이다")
    void cancelledAtComesFromInjectedClock() {
        when(reportRepository.findByPublicIdAndReporterId(PUBLIC_ID, REPORTER))
                .thenReturn(Optional.of(receivedReport()));
        when(reportRepository.cancelByReporter(eq(99L), eq(REPORTER), any(Instant.class))).thenReturn(1);

        reportCommandService.cancel(REPORTER, PUBLIC_ID);

        verify(reportRepository).cancelByReporter(99L, REPORTER, MutableClock.DEFAULT);
    }

    @Test
    @DisplayName("없거나 남의 신고면 404(C002)다 — 조건부 UPDATE는 부르지 않는다")
    void rejectsUnknownOrOthersReportWithNotFound() {
        when(reportRepository.findByPublicIdAndReporterId(PUBLIC_ID, REPORTER))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> reportCommandService.cancel(REPORTER, PUBLIC_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
        verify(reportRepository, never()).cancelByReporter(any(), any(), any());
    }

    @Test
    @DisplayName("이미 검토 중·처리된 신고는 409(C004)다 — 조건부 UPDATE가 0행을 돌려준 경우")
    void rejectsNonReceivedReportWithConflict() {
        when(reportRepository.findByPublicIdAndReporterId(PUBLIC_ID, REPORTER))
                .thenReturn(Optional.of(receivedReport()));
        when(reportRepository.cancelByReporter(eq(99L), eq(REPORTER), any(Instant.class))).thenReturn(0);

        assertThatThrownBy(() -> reportCommandService.cancel(REPORTER, PUBLIC_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.CONFLICT);
    }

    private Report reportIn(ReportStatus status) {
        Report report = receivedReport();
        ReflectionTestUtils.setField(report, "status", status);
        return report;
    }

    @Test
    @DisplayName("RPT-F4 이미 취소한 신고를 다시 취소하면 그대로 성공한다 - 조건부 UPDATE도 부르지 않는다(멱등)")
    void cancellingCancelledReportIsNoOp() {
        when(reportRepository.findByPublicIdAndReporterId(PUBLIC_ID, REPORTER))
                .thenReturn(Optional.of(reportIn(ReportStatus.CANCELLED)));

        assertThatCode(() -> reportCommandService.cancel(REPORTER, PUBLIC_ID)).doesNotThrowAnyException();
        verify(reportRepository, never()).cancelByReporter(any(), any(), any());
    }

    @Test
    @DisplayName("RPT-F5 검토 중·처리·기각된 신고는 전이표로 먼저 걸러 409(C004) - 조건부 UPDATE를 부르지 않는다")
    void nonCancellableStatusIsRejectedByTransitionTable() {
        for (ReportStatus status : new ReportStatus[] {ReportStatus.REVIEWING, ReportStatus.RESOLVED, ReportStatus.REJECTED}) {
            when(reportRepository.findByPublicIdAndReporterId(PUBLIC_ID, REPORTER))
                    .thenReturn(Optional.of(reportIn(status)));

            assertThatThrownBy(() -> reportCommandService.cancel(REPORTER, PUBLIC_ID))
                    .as(status.name())
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.CONFLICT);
        }
        verify(reportRepository, never()).cancelByReporter(any(), any(), any());
    }

    @Test
    @DisplayName("RPT-F4 동시에 두 번 취소해 조건부 UPDATE가 0행이어도, 다시 읽어 이미 취소돼 있으면 성공한다")
    void concurrentDoubleCancelSucceeds() {
        when(reportRepository.findByPublicIdAndReporterId(PUBLIC_ID, REPORTER))
                .thenReturn(Optional.of(receivedReport()), Optional.of(reportIn(ReportStatus.CANCELLED)));
        when(reportRepository.cancelByReporter(eq(99L), eq(REPORTER), any(Instant.class))).thenReturn(0);

        assertThatCode(() -> reportCommandService.cancel(REPORTER, PUBLIC_ID)).doesNotThrowAnyException();
    }
}
