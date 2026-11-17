package com.safedeal.domain.report.service;

import com.safedeal.domain.report.dto.ReportCreateRequest;
import com.safedeal.domain.report.dto.ReportCreateResponse;
import com.safedeal.domain.report.entity.Report;
import com.safedeal.domain.report.entity.ReportReason;
import com.safedeal.domain.report.entity.ReportStatus;
import com.safedeal.domain.report.entity.ReportTargetType;
import com.safedeal.domain.report.exception.ReportErrorCode;
import com.safedeal.domain.report.repository.ReportRepository;
import com.safedeal.global.exception.BusinessException;
import com.safedeal.global.exception.CommonErrorCode;
import com.safedeal.testsupport.MutableClock;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 신고 접수의 진입점 {@code ReportCommandService.receive} — 트랜잭션 경계 밖 처리만 본다
 * ({@code ChatRoomCommandService}와 같은 구조, 결정 D1). 검증·중복 판정·저장은 {@link ReportCreator}
 * (별도 테스트)가 한 트랜잭션에서 하고, 여기서는 UNIQUE 충돌 시 새 트랜잭션으로 한 번 다시 시도하는 것과
 * 잠금 예외 변환만 고정한다.
 */
@ExtendWith(MockitoExtension.class)
class ReportCommandServiceReceiveTest {

    @Mock ReportRepository reportRepository;
    @Mock ReportCreator reportCreator;
    @Spy MutableClock clock = new MutableClock();

    @InjectMocks ReportCommandService reportCommandService;

    private static final Long REPORTER = 20L;
    private static final ReportCreateRequest REQUEST = new ReportCreateRequest(
            ReportTargetType.LISTING, "01J3ARSNIPLISTING000000001", ReportReason.FRAUD, null, null);

    /** 실제 MySQL이 돌려주는 UNIQUE 위반 모양(Spring → Hibernate → 드라이버). chat 쪽 픽스처와 같은 구조다. */
    private static DataIntegrityViolationException uniqueViolation(String constraint) {
        String qualified = "reports." + constraint;
        SQLIntegrityConstraintViolationException driver = new SQLIntegrityConstraintViolationException(
                "Duplicate entry '20-LISTING-10-FRAUD' for key '" + qualified + "'");
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("could not execute statement", driver, "insert ...", qualified));
    }

    private void assertErrorCode(Object expected) {
        assertThatThrownBy(() -> reportCommandService.receive(REPORTER, REQUEST))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("RPT-C37 진입점 receive에는 트랜잭션이 없고 한 번의 시도(ReportCreator.receive)에만 있다 - 재시도가 새 트랜잭션이어야 한다")
    void onlyCreatorIsTransactional() throws NoSuchMethodException {
        var entry = ReportCommandService.class.getMethod("receive", Long.class, ReportCreateRequest.class);
        var attempt = ReportCreator.class.getMethod("receive", Long.class, ReportCreateRequest.class);

        // 클래스에 붙어 있어도 메서드에 적용되므로 둘 다 본다.
        assertThat(AnnotatedElementUtils.hasAnnotation(entry, Transactional.class)).isFalse();
        assertThat(AnnotatedElementUtils.hasAnnotation(ReportCommandService.class, Transactional.class)).isFalse();
        assertThat(AnnotatedElementUtils.hasAnnotation(attempt, Transactional.class)
                || AnnotatedElementUtils.hasAnnotation(ReportCreator.class, Transactional.class)).isTrue();
    }

    @Test
    @DisplayName("RPT-C01 첫 시도가 성공하면 그 응답을 그대로 돌려주고 한 번만 시도한다")
    void returnsFirstAttempt() {
        ReportCreateResponse expected = new ReportCreateResponse(
                "01J3ARSNIPREPORT0000000001", ReportStatus.RECEIVED, Instant.parse("2026-11-15T01:00:00Z"));
        when(reportCreator.receive(REPORTER, REQUEST)).thenReturn(expected);

        assertThat(reportCommandService.receive(REPORTER, REQUEST)).isEqualTo(expected);
        verify(reportCreator, times(1)).receive(REPORTER, REQUEST);
    }

    @Test
    @DisplayName("RPT-C37 동시 접수에서 진 쪽은 UNIQUE 위반 뒤 새 트랜잭션으로 다시 시도해 409(RPT001)를 받는다")
    void retriesOnceOnReporterTargetReasonViolation() {
        when(reportCreator.receive(REPORTER, REQUEST))
                .thenThrow(uniqueViolation(Report.UK_REPORTER_TARGET_REASON))
                .thenThrow(new BusinessException(ReportErrorCode.DUPLICATE_ACTIVE_REPORT));

        assertErrorCode(ReportErrorCode.DUPLICATE_ACTIVE_REPORT);
        verify(reportCreator, times(2)).receive(REPORTER, REQUEST);
    }

    @Test
    @DisplayName("RPT-C37 재시도에서도 또 UNIQUE 위반이면 삼키지 않고 그대로 올린다 (반복하지 않는다)")
    void secondViolationIsNotSwallowed() {
        DataIntegrityViolationException second = uniqueViolation(Report.UK_REPORTER_TARGET_REASON);
        when(reportCreator.receive(REPORTER, REQUEST))
                .thenThrow(uniqueViolation(Report.UK_REPORTER_TARGET_REASON))
                .thenThrow(second);

        assertThatThrownBy(() -> reportCommandService.receive(REPORTER, REQUEST)).isSameAs(second);
        verify(reportCreator, times(2)).receive(REPORTER, REQUEST);
    }

    @Test
    @DisplayName("RPT-C44 제약 이름을 알 수 없는 무결성 위반은 재시도 없이 그대로 올린다 (공통 처리기가 409 C004로 응답)")
    void unknownViolationIsNotRetried() {
        DataIntegrityViolationException unknown = new DataIntegrityViolationException("원인 불명");
        when(reportCreator.receive(REPORTER, REQUEST)).thenThrow(unknown);

        assertThatThrownBy(() -> reportCommandService.receive(REPORTER, REQUEST)).isSameAs(unknown);
        verify(reportCreator, times(1)).receive(REPORTER, REQUEST);
    }

    @Test
    @DisplayName("RPT-C44 다른 제약(public_id) 위반도 재시도하지 않는다 - 새 ULID로 우연히 성공해 원인이 가려지지 않게")
    void publicIdViolationIsNotRetried() {
        DataIntegrityViolationException publicId = uniqueViolation(Report.UK_PUBLIC_ID);
        when(reportCreator.receive(REPORTER, REQUEST)).thenThrow(publicId);

        assertThatThrownBy(() -> reportCommandService.receive(REPORTER, REQUEST)).isSameAs(publicId);
        verify(reportCreator, times(1)).receive(REPORTER, REQUEST);
    }

    @Test
    @DisplayName("RPT-C43 부활 잠금 대기 타임아웃(CannotAcquireLockException)은 409(C009)로 바꾼다 (Q10)")
    void lockTimeoutBecomesConcurrentModification() {
        when(reportCreator.receive(REPORTER, REQUEST))
                .thenThrow(new CannotAcquireLockException("Lock wait timeout exceeded"));

        assertErrorCode(CommonErrorCode.CONCURRENT_MODIFICATION);
        verify(reportCreator, times(1)).receive(REPORTER, REQUEST);
    }

    @Test
    @DisplayName("RPT-C43 상위 타입 PessimisticLockingFailureException도 409(C009)로 바꾼다 - 드라이버·방언마다 잠금 실패 타입이 다르다")
    void pessimisticLockFailureBecomesConcurrentModification() {
        when(reportCreator.receive(REPORTER, REQUEST))
                .thenThrow(new PessimisticLockingFailureException("could not obtain lock"));

        assertErrorCode(CommonErrorCode.CONCURRENT_MODIFICATION);
        verify(reportCreator, times(1)).receive(REPORTER, REQUEST);
    }
}
