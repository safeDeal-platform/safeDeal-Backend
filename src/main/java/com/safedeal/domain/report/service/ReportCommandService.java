package com.safedeal.domain.report.service;

import com.safedeal.domain.report.dto.ReportCreateRequest;
import com.safedeal.domain.report.dto.ReportCreateResponse;
import com.safedeal.domain.report.entity.Report;
import com.safedeal.domain.report.entity.ReportStatus;
import com.safedeal.domain.report.repository.ReportRepository;
import com.safedeal.global.exception.BusinessException;
import com.safedeal.global.exception.CommonErrorCode;
import com.safedeal.global.util.UniqueViolations;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 신고 명령의 진입점. 클래스에 {@code @Transactional}을 두지 않는다 — 접수({@link #receive})는 재시도가 완전히
 * 새 트랜잭션으로 시작해야 해서 트랜잭션 밖에 있고({@code ChatRoomCommandService}와 같은 이유, D1),
 * 취소({@link #cancel})만 메서드에 트랜잭션을 건다.
 */
@Service
@RequiredArgsConstructor
public class ReportCommandService {

    private final ReportRepository reportRepository;
    private final ReportCreator reportCreator;

    /**
     * 신고 접수. 같은 신고가 동시에 들어와 둘 다 중복 판정을 통과하면 진 쪽은
     * {@link Report#UK_REPORTER_TARGET_REASON} 위반으로 실패한다 — 그때만 한 번 다시 시도한다(이긴 쪽이
     * 커밋했으니 이번엔 중복 판정에서 RPT001로 끝난다, RPT-C37). 다른 제약 위반이나 이름을 못 읽는 위반은
     * 원인이 다르므로 그대로 올린다(RPT-C44, 공통 처리기가 409 C004로 응답).
     *
     * <p>부활 경로의 행 잠금을 제때 못 얻으면 409(C009)로 바꾼다 — 잠시 뒤 다시 보내면 성공할 수 있다(Q10).
     */
    public ReportCreateResponse receive(Long reporterId, ReportCreateRequest request) {
        try {
            return attemptReceive(reporterId, request);
        } catch (DataIntegrityViolationException e) {
            if (!UniqueViolations.causedBy(e, Report.UK_REPORTER_TARGET_REASON)) {
                throw e;
            }
            return attemptReceive(reporterId, request);
        }
    }

    private ReportCreateResponse attemptReceive(Long reporterId, ReportCreateRequest request) {
        try {
            return reportCreator.receive(reporterId, request);
        } catch (PessimisticLockingFailureException e) {
            throw new BusinessException(CommonErrorCode.CONCURRENT_MODIFICATION);
        }
    }

    /**
     * 신고자 본인의 취소. 접수(RECEIVED) 상태일 때만 된다 — 검토가 시작된 뒤에는
     * 취소할 수 없다(사용자 확정 2026-10-05: REVIEWING 이후는 409).
     *
     * <p>일일 신고 한도(RPT003)는 취소해도 돌려주지 않는다 — Redis 카운터를 깎는 보정은
     * 범위 밖이고, 한도 자체가 fail-open이라 정확한 차감을 보장할 수 없다.
     *
     * <p>남의 신고와 없는 신고를 구분하지 않는다. 둘 다 404(C002)다 — 신고 존재 여부를
     * 알려주면 "누가 날 신고했는지"를 역추적하는 통로가 된다({@code FavoriteCommandService}와
     * 같은 원칙).
     */
    @Transactional
    public void cancel(Long reporterId, String reportPublicId) {
        Report report = reportRepository.findByPublicIdAndReporterId(reportPublicId, reporterId)
                .orElseThrow(ReportCommandService::notFound);

        // RPT-F4: 이미 취소된 신고를 다시 취소하면 그대로 성공(멱등) — 두 번 누른 사용자에게 틀린 안내를 주지 않는다.
        if (report.getStatus() == ReportStatus.CANCELLED) {
            return;
        }
        // RPT-F5: 전이표로 먼저 거르고(팀 규칙: 전이표 + 조건부 UPDATE), 조건부 UPDATE로 확정한다.
        if (!report.getStatus().canTransitionTo(ReportStatus.CANCELLED)) {
            throw notCancellable();
        }
        int updated = reportRepository.cancelByReporter(report.getId(), reporterId, Instant.now());
        if (updated == 0) {
            // 그사이 상태가 바뀌었다. 동시에 두 번 취소한 경우라면 이미 CANCELLED이므로 성공으로 본다.
            boolean alreadyCancelled = reportRepository.findByPublicIdAndReporterId(reportPublicId, reporterId)
                    .map(r -> r.getStatus() == ReportStatus.CANCELLED)
                    .orElse(false);
            if (!alreadyCancelled) {
                throw notCancellable();
            }
        }
    }

    private static BusinessException notCancellable() {
        return new BusinessException(CommonErrorCode.CONFLICT, "이미 검토가 시작되었거나 처리된 신고는 취소할 수 없습니다.");
    }

    private static BusinessException notFound() {
        return new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "신고를 찾을 수 없습니다.");
    }
}
