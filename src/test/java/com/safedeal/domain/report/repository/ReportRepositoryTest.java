package com.safedeal.domain.report.repository;

import com.safedeal.domain.report.entity.Report;
import com.safedeal.domain.report.entity.ReportReason;
import com.safedeal.domain.report.entity.ReportTargetType;
import com.safedeal.testsupport.IntegrationTestSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 신고 테이블의 제약과 취소 조건부 UPDATE가 실제 MySQL에서 도는지 확인한다.
 * 파생 쿼리와 JPQL 리터럴(enum)이 Hibernate에서 맞게 해석되는지도 여기서 본다.
 */
@Transactional
class ReportRepositoryTest extends IntegrationTestSupport {

    private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");
    private static final Long REPORTER = 20L;
    private static final Long OTHER_USER = 99L;

    @Autowired
    ReportRepository reportRepository;

    @Autowired
    EntityManager entityManager;

    private Report saveListingReport(Long reporter, ReportReason reason, String publicId) {
        return reportRepository.saveAndFlush(Report.receive(publicId, reporter,
                ReportTargetType.LISTING, 10L, "01J3ARSNIPLISTING000001", 30L,
                reason, null, null, NOW));
    }

    @Test
    @DisplayName("같은 사람·대상·사유를 두 번 저장하면 UNIQUE 위반으로 막힌다 (중복 신고 방지)")
    void sameReporterTargetReasonViolatesUnique() {
        saveListingReport(REPORTER, ReportReason.FRAUD, "01J3ARSNIPREPORT0000000001");

        assertThatThrownBy(() -> saveListingReport(REPORTER, ReportReason.FRAUD, "01J3ARSNIPREPORT0000000002"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 대상이라도 사유가 다르면 재신고가 허용된다 (정책: 다른 문제가 생겼는데 영영 불가하면 안 된다)")
    void differentReasonAllowed() {
        saveListingReport(REPORTER, ReportReason.FRAUD, "01J3ARSNIPREPORT0000000001");
        saveListingReport(REPORTER, ReportReason.SPAM, "01J3ARSNIPREPORT0000000002");

        Optional<Report> spam = reportRepository.findByReporterIdAndTargetTypeAndTargetIdAndReasonCode(
                REPORTER, ReportTargetType.LISTING, 10L, ReportReason.SPAM);
        assertThat(spam).isPresent();
    }

    @Test
    @DisplayName("상태는 문자열(RECEIVED)로 저장된다 - 순서 번호가 아니다 (EnumType.STRING)")
    void statusStoredAsString() {
        Report saved = saveListingReport(REPORTER, ReportReason.FRAUD, "01J3ARSNIPREPORT0000000001");

        String stored = (String) entityManager
                .createNativeQuery("SELECT status FROM reports WHERE id = :id")
                .setParameter("id", saved.getId())
                .getSingleResult();
        assertThat(stored).isEqualTo("RECEIVED");
    }

    @Test
    @DisplayName("신고자 본인이 접수 상태의 신고를 취소하면 1행이 바뀌고 CANCELLED가 된다")
    void ownerCancelsReceivedReport() {
        Report saved = saveListingReport(REPORTER, ReportReason.FRAUD, "01J3ARSNIPREPORT0000000001");

        int updated = reportRepository.cancelByReporter(saved.getId(), REPORTER, NOW);
        entityManager.clear();

        assertThat(updated).isEqualTo(1);
        assertThat(reportRepository.findById(saved.getId()).orElseThrow().getStatus().name())
                .isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("이미 취소된 신고는 다시 취소되지 않는다 - 두 번째 호출은 0행")
    void alreadyCancelledIsNoop() {
        Report saved = saveListingReport(REPORTER, ReportReason.FRAUD, "01J3ARSNIPREPORT0000000001");
        reportRepository.cancelByReporter(saved.getId(), REPORTER, NOW);

        int second = reportRepository.cancelByReporter(saved.getId(), REPORTER, NOW);

        assertThat(second).isZero();
    }

    @Test
    @DisplayName("남의 신고는 취소할 수 없다 - 0행이고 상태도 그대로다")
    void nonOwnerCannotCancel() {
        Report saved = saveListingReport(REPORTER, ReportReason.FRAUD, "01J3ARSNIPREPORT0000000001");

        int updated = reportRepository.cancelByReporter(saved.getId(), OTHER_USER, NOW);
        entityManager.clear();

        assertThat(updated).isZero();
        assertThat(reportRepository.findById(saved.getId()).orElseThrow().getStatus().name())
                .isEqualTo("RECEIVED");
    }

    @Test
    @DisplayName("검토가 시작된 신고는 취소할 수 없다 - 0행이고 REVIEWING 그대로다 (기본값: 검토 중 취소 금지)")
    void reviewingCannotBeCancelled() {
        Report saved = saveListingReport(REPORTER, ReportReason.FRAUD, "01J3ARSNIPREPORT0000000001");
        entityManager.createNativeQuery("UPDATE reports SET status = 'REVIEWING' WHERE id = :id")
                .setParameter("id", saved.getId())
                .executeUpdate();
        entityManager.clear();

        int updated = reportRepository.cancelByReporter(saved.getId(), REPORTER, NOW);
        entityManager.clear();

        assertThat(updated).isZero();
        assertThat(reportRepository.findById(saved.getId()).orElseThrow().getStatus().name())
                .isEqualTo("REVIEWING");
    }
}
