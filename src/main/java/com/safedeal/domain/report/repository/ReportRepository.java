package com.safedeal.domain.report.repository;

import com.safedeal.domain.report.entity.Report;
import com.safedeal.domain.report.entity.ReportReason;
import com.safedeal.domain.report.entity.ReportTargetType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface ReportRepository extends JpaRepository<Report, Long>, ReportLockingRepository {

    /** 같은 사람·대상·사유의 신고가 있는지(중복 판정·재신고 부활의 기준 행). 조건 순서는 UNIQUE 컬럼 순서와 맞춘다. */
    Optional<Report> findByReporterIdAndTargetTypeAndTargetIdAndReasonCode(
            Long reporterId, ReportTargetType targetType, Long targetId, ReportReason reasonCode);

    /**
     * 공개 id로 신고를 찾되, 신고자 조건을 쿼리 자체에 박아둔다. 신고자 본인이 아니면
     * "권한 없음"이 아니라 "없음"으로 응답한다 — {@code ChatRoomRepository.findByPublicIdAndParticipant}와
     * 같은 원칙(존재 여부 자체를 알려주지 않는다). 취소 API가 내부 id를 얻으려고 먼저 부른다.
     *
     * <p>RPT-F4: 행 잠금(FOR UPDATE)으로 읽는다. MySQL 기본 격리(REPEATABLE READ)에서 잠금 없는 읽기는
     * 트랜잭션 첫 읽기 시점의 스냅샷을 보므로, 동시에 두 번 취소하면 뒤 요청이 이미 취소된 상태를 보지 못하고
     * 409를 돌려준다. 잠금으로 뒤 요청을 앞 요청 커밋 뒤로 미뤄 최신 상태(CANCELLED)를 보게 한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Report> findByPublicIdAndReporterId(String publicId, Long reporterId);

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

    /**
     * 같은 사유의 재신고: 취소(CANCELLED)된 행일 때만 접수(RECEIVED)로 되살린다. 상세·증거는 요청 값으로
     * 덮어쓰고(null이면 null, Q8), 큐 정렬 기준인 {@code received_at}을 새로 찍는다. {@code created_at}과
     * 공개 id는 그대로다.
     *
     * <p>영향 행이 0이면 그사이 다른 요청이 먼저 되살렸거나 상태가 바뀐 것이다(RPT001로 응답한다).
     * 벌크 UPDATE라 {@code updated_at}을 직접 채우고, {@link #cancelByReporter}와 같은 이유로 컨텍스트를 비운다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Report r set r.status = com.safedeal.domain.report.entity.ReportStatus.RECEIVED, "
            + "r.detail = :detail, r.evidenceMessageId = :evidenceMessageId, "
            + "r.receivedAt = :now, r.updatedAt = :now "
            + "where r.id = :id "
            + "and r.status = com.safedeal.domain.report.entity.ReportStatus.CANCELLED")
    int reviveCancelled(@Param("id") Long id, @Param("detail") String detail,
                        @Param("evidenceMessageId") Long evidenceMessageId, @Param("now") Instant now);
}
