package com.safedeal.domain.report.repository;

import com.safedeal.domain.report.entity.Report;
import com.safedeal.domain.report.entity.ReportReason;
import com.safedeal.domain.report.entity.ReportTargetType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface ReportRepository extends JpaRepository<Report, Long> {

    /** 같은 사람·대상·사유의 신고가 있는지(중복 판정·재신고 부활의 기준 행). 조건 순서는 UNIQUE 컬럼 순서와 맞춘다. */
    Optional<Report> findByReporterIdAndTargetTypeAndTargetIdAndReasonCode(
            Long reporterId, ReportTargetType targetType, Long targetId, ReportReason reasonCode);

    /**
     * 신고자 본인의 취소: 접수 상태(RECEIVED)일 때만 취소(CANCELLED)로 바꾼다.
     *
     * <p>영향 행이 0이면 둘 중 하나다. 본인 신고가 아니거나(없는 것과 같게 404로 응답한다),
     * 이미 검토가 시작됐거나 취소된 상태다(409로 응답한다). 어느 쪽인지는 서비스가 다시 확인한다.
     *
     * <p>벌크 UPDATE라 {@code updated_at}을 직접 채운다. 영속성 컨텍스트의 낡은 객체를 막으려고
     * {@code clearAutomatically}도 둔다(채팅방 읽음 커서와 같은 이유).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Report r set r.status = com.safedeal.domain.report.entity.ReportStatus.CANCELLED, "
            + "r.updatedAt = :now "
            + "where r.id = :id and r.reporterId = :reporterId "
            + "and r.status = com.safedeal.domain.report.entity.ReportStatus.RECEIVED")
    int cancelByReporter(@Param("id") Long id, @Param("reporterId") Long reporterId,
                         @Param("now") Instant now);
}
