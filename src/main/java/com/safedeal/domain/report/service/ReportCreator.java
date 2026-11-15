package com.safedeal.domain.report.service;

import com.safedeal.domain.auth.exception.AuthErrorCode;
import com.safedeal.domain.chat.entity.ChatRoom;
import com.safedeal.domain.chat.repository.ChatMessageRepository;
import com.safedeal.domain.chat.repository.ChatRoomRepository;
import com.safedeal.domain.listing.entity.Listing;
import com.safedeal.domain.listing.entity.ListingStatus;
import com.safedeal.domain.listing.repository.ListingRepository;
import com.safedeal.domain.report.dto.ReportCreateRequest;
import com.safedeal.domain.report.dto.ReportCreateResponse;
import com.safedeal.domain.report.entity.Report;
import com.safedeal.domain.report.entity.ReportStatus;
import com.safedeal.domain.report.entity.ReportTargetType;
import com.safedeal.domain.report.exception.ReportErrorCode;
import com.safedeal.domain.report.repository.ReportDailyLimiter;
import com.safedeal.domain.report.repository.ReportRepository;
import com.safedeal.domain.user.entity.User;
import com.safedeal.domain.user.entity.UserStatus;
import com.safedeal.domain.user.repository.UserRepository;
import com.safedeal.global.exception.BusinessException;
import com.safedeal.global.exception.CommonErrorCode;
import com.safedeal.global.util.PublicIdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 신고 접수의 단일 시도(트랜잭션 경계). {@link ReportCommandService}가 UNIQUE 충돌 시 이 메서드를 새 트랜잭션으로
 * 한 번 다시 부른다({@code ChatRoomCreator}와 같은 구조, D1).
 *
 * <p>순서: 신고자 → 대상·피신고자 → 증거 메시지 → 엔티티 규칙(사유×대상, 길이 등) → 중복 판정 → 하루 한도 →
 * 저장 또는 부활. 한도는 다른 모든 판정을 통과한 뒤에만 센다 — 실패한 시도가 한도를 깎지 않게 한다(Q2).
 *
 * <p>접수 시 이벤트 발행·피신고자 알림은 하지 않는다(Q11).
 */
@Service
@RequiredArgsConstructor
public class ReportCreator {

    private final ReportRepository reportRepository;
    private final ListingRepository listingRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final UserRepository userRepository;
    private final ReportDailyLimiter dailyLimiter;
    private final PublicIdGenerator publicIdGenerator;

    /** 확인된 신고 대상. 피신고자는 대상 종류로 정해지는 값이라 여기서 확정한다. */
    private record Target(Long id, String publicId, Long accusedUserId) {
    }

    @Transactional
    public ReportCreateResponse receive(Long reporterId, ReportCreateRequest request) {
        requireActiveReporter(reporterId);
        Target target = request.targetType() == ReportTargetType.LISTING
                ? listingTarget(reporterId, request.targetId())
                : chatTarget(reporterId, request.targetId(), request.evidenceMessageId());

        Instant now = Instant.now();
        // 엔티티 팩토리가 사유×대상·증거 유무·길이를 막고 상세를 다듬는다. 부활 경로도 같은 규칙과 값을 쓴다.
        Report candidate = Report.receive(publicIdGenerator.generate(), reporterId, request.targetType(),
                target.id(), target.publicId(), target.accusedUserId(), request.reasonCode(),
                request.detail(), request.evidenceMessageId(), now);

        Report existing = reportRepository.findByReporterIdAndTargetTypeAndTargetIdAndReasonCode(
                reporterId, request.targetType(), target.id(), request.reasonCode()).orElse(null);
        if (existing == null) {
            acquireDailyLimit(reporterId, now);
            Report saved = reportRepository.save(candidate);
            return new ReportCreateResponse(saved.getPublicId(), saved.getStatus(), saved.getReceivedAt());
        }
        return revive(existing, candidate, now);
    }

    /**
     * 취소된 신고를 같은 행으로 되살린다(RPT-C34). 첫 조회는 잠금 없는 스냅샷이라, 동시에 되살리는 요청(RPT-C38)이나
     * 취소(RPT-C39)와 직렬화하려고 행 잠금을 걸고 다시 판정한다. 이때 잠금 조회만 하면 영속성 컨텍스트(1차 캐시)에
     * 남은 첫 조회의 옛 객체가 그대로 돌아오므로, 잠금과 함께 DB 값으로 refresh한 결과로 판정한다 — 진 요청이
     * 옛 CANCELLED를 믿고 한도를 쓰지 않게 한다(RPT-C51, Q2). 최종 확정은 조건부 UPDATE다.
     */
    private ReportCreateResponse revive(Report existing, Report candidate, Instant now) {
        rejectUnlessCancelled(existing);
        Report locked = reportRepository.refreshForUpdate(existing);
        rejectUnlessCancelled(locked);

        acquireDailyLimit(candidate.getReporterId(), now);
        int updated = reportRepository.reviveCancelled(
                locked.getId(), candidate.getDetail(), candidate.getEvidenceMessageId(), now);
        if (updated == 0) {
            throw new BusinessException(ReportErrorCode.DUPLICATE_ACTIVE_REPORT);
        }
        return new ReportCreateResponse(locked.getPublicId(), ReportStatus.RECEIVED, now);
    }

    /** 같은 사유의 신고가 살아 있으면 RPT001, 종결됐으면 RPT002. 취소 상태만 통과한다. */
    private static void rejectUnlessCancelled(Report report) {
        switch (report.getStatus()) {
            case CANCELLED -> {
            }
            case RESOLVED, REJECTED -> throw new BusinessException(ReportErrorCode.REPORT_ALREADY_CLOSED);
            default -> throw new BusinessException(ReportErrorCode.DUPLICATE_ACTIVE_REPORT);
        }
    }

    private void acquireDailyLimit(Long reporterId, Instant now) {
        if (!dailyLimiter.tryAcquire(reporterId, now)) {
            throw new BusinessException(ReportErrorCode.DAILY_REPORT_LIMIT_EXCEEDED);
        }
    }

    /** D5: 계정이 없으면(탈퇴 직후 토큰) 401, Q7: 정지된 신고자는 403. */
    private void requireActiveReporter(Long reporterId) {
        User reporter = userRepository.findByIdAndDeletedAtIsNull(reporterId)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.INVALID_TOKEN));
        if (reporter.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN, "이용이 제한된 계정은 신고할 수 없습니다.");
        }
    }

    /**
     * 매물 대상. 순서가 정책이다: 없음·삭제 404 → 본인 매물 RPT006(D3: BLOCKED보다 먼저) → 제재된 매물 RPT007 →
     * 공개 전(DRAFT·PENDING_VERIFICATION) 404(Q3) → 판매자 탈퇴 404·정지 RPT007(Q4). SOLD는 신고할 수 있다.
     */
    private Target listingTarget(Long reporterId, String listingPublicId) {
        Listing listing = listingRepository.findByPublicIdAndDeletedAtIsNull(listingPublicId)
                .orElseThrow(() -> notFound("매물을 찾을 수 없습니다."));
        if (listing.isOwnedBy(reporterId)) {
            throw new BusinessException(ReportErrorCode.SELF_REPORT_NOT_ALLOWED);
        }
        if (listing.getStatus() == ListingStatus.BLOCKED) {
            throw new BusinessException(ReportErrorCode.TARGET_ALREADY_SANCTIONED);
        }
        if (!listing.isViewable()) {
            throw notFound("매물을 찾을 수 없습니다.");
        }
        requireReportableAccused(listing.getSellerId());
        return new Target(listing.getId(), listing.getPublicId(), listing.getSellerId());
    }

    /**
     * 채팅 대상. 참여자가 아니면 방 존재를 알려주지 않고 404(RPT-C10). 숨긴 방도 참여자면 된다(Q12).
     * 피신고자는 방 상대이고, 증거는 "이 방에서 상대가 보낸 메시지"여야 한다(RPT-C11~C14).
     */
    private Target chatTarget(Long reporterId, String roomPublicId, Long evidenceMessageId) {
        ChatRoom room = chatRoomRepository.findByPublicIdAndParticipant(roomPublicId, reporterId)
                .orElseThrow(() -> notFound("채팅방을 찾을 수 없습니다."));
        Long partnerId = room.isBuyer(reporterId) ? room.getSellerId() : room.getBuyerId();
        requireReportableAccused(partnerId);
        if (evidenceMessageId == null
                || !chatMessageRepository.existsByIdAndRoomIdAndSenderId(evidenceMessageId, room.getId(), partnerId)) {
            throw new BusinessException(ReportErrorCode.INVALID_EVIDENCE_MESSAGE);
        }
        return new Target(room.getId(), room.getPublicId(), partnerId);
    }

    /** 피신고자가 탈퇴했으면 404(Q4·D2), 이미 정지됐으면 제재가 끝난 대상이라 RPT007(RPT-C18·C19). */
    private void requireReportableAccused(Long accusedUserId) {
        User accused = userRepository.findByIdAndDeletedAtIsNull(accusedUserId)
                .orElseThrow(() -> notFound("신고 대상을 찾을 수 없습니다."));
        if (accused.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ReportErrorCode.TARGET_ALREADY_SANCTIONED);
        }
    }

    private static BusinessException notFound(String message) {
        return new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, message);
    }
}
