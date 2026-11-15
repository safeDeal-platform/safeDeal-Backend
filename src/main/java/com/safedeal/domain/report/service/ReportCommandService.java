package com.safedeal.domain.report.service;

import com.safedeal.domain.report.entity.Report;
import com.safedeal.domain.report.entity.ReportStatus;
import com.safedeal.domain.report.repository.ReportRepository;
import com.safedeal.global.exception.BusinessException;
import com.safedeal.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
@Transactional
public class ReportCommandService {

    private final ReportRepository reportRepository;

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
