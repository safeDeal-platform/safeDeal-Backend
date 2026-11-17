package com.safedeal.domain.report.service;

import com.safedeal.domain.chat.entity.ChatRoom;
import com.safedeal.domain.listing.entity.Listing;
import com.safedeal.domain.report.ReportCreateIntegrationSupport;
import com.safedeal.domain.report.dto.ReportCreateResponse;
import com.safedeal.domain.report.entity.Report;
import com.safedeal.domain.report.entity.ReportReason;
import com.safedeal.domain.report.entity.ReportStatus;
import com.safedeal.domain.report.exception.ReportErrorCode;
import com.safedeal.domain.report.repository.ReportDailyLimiter;
import com.safedeal.domain.user.entity.User;
import com.safedeal.global.exception.BusinessException;
import com.safedeal.global.exception.CommonErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 신고 접수를 실제 MySQL·Redis로 끝까지 돌린다 — UNIQUE 제약, 부활 조건부 UPDATE, 참여자 조회가
 * 목이 아니라 진짜 쿼리로 맞는지 본다.
 */
class ReportCreateIntegrationTest extends ReportCreateIntegrationSupport {

    @Autowired ReportCommandService reportCommandService;
    @Autowired ReportDailyLimiter dailyLimiter;

    private User reporter;
    private User seller;
    private Listing listing;

    @BeforeEach
    void setUp() {
        reporter = user();
        seller = user();
        listing = activeListing(seller);
    }

    private Report onlyReport() {
        assertThat(reportRepository.findAll()).hasSize(1);
        return reportRepository.findAll().get(0);
    }

    private static void assertErrorCode(Runnable call, Object expected) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("RPT-C01 매물 신고가 RECEIVED로 저장되고 피신고자·대상 공개 id가 기록된다")
    void listingReportIsPersisted() {
        ReportCreateResponse response = reportCommandService.receive(
                reporter.getId(), listingRequest(listing, ReportReason.FRAUD, "허위 매물"));

        Report saved = onlyReport();
        assertThat(saved.getPublicId()).isEqualTo(response.reportId());
        assertThat(saved.getStatus()).isEqualTo(ReportStatus.RECEIVED);
        assertThat(saved.getAccusedUserId()).isEqualTo(seller.getId());
        assertThat(saved.getTargetId()).isEqualTo(listing.getId());
        assertThat(saved.getTargetPublicId()).isEqualTo(listing.getPublicId());
        assertThat(response.status()).isEqualTo(ReportStatus.RECEIVED);
    }

    @Test
    @DisplayName("RPT-C02 채팅 신고는 방 상대가 피신고자이고 증거 메시지 id가 저장된다")
    void chatReportIsPersisted() {
        ChatRoom room = room(listing, reporter);
        Long evidence = message(room, seller);

        reportCommandService.receive(reporter.getId(), chatRequest(room, evidence, "욕설"));

        Report saved = onlyReport();
        assertThat(saved.getAccusedUserId()).isEqualTo(seller.getId());
        assertThat(saved.getTargetId()).isEqualTo(room.getId());
        assertThat(saved.getEvidenceMessageId()).isEqualTo(evidence);
    }

    @Test
    @DisplayName("RPT-C02 숨긴(나간) 채팅방이라도 참여자면 실제 조회에서도 신고된다 (Q12)")
    void hiddenRoomCanBeReported() {
        ChatRoom room = room(listing, reporter);
        Long evidence = message(room, seller);
        jdbcTemplate.update("UPDATE chat_rooms SET buyer_hidden_at = '2026-11-10 00:00:00' WHERE id = ?", room.getId());

        reportCommandService.receive(reporter.getId(), chatRequest(room, evidence, null));

        assertThat(onlyReport().getTargetId()).isEqualTo(room.getId());
    }

    @Test
    @DisplayName("RPT-C12 같은 상대가 다른 방에서 보낸 메시지를 증거로 내면 400(RPT005)이고 저장되지 않는다")
    void evidenceFromOtherRoomIsRejected() {
        ChatRoom room = room(listing, reporter);
        ChatRoom otherRoom = room(activeListing(seller), reporter);
        Long otherRoomMessage = message(otherRoom, seller);

        assertErrorCode(() -> reportCommandService.receive(reporter.getId(), chatRequest(room, otherRoomMessage, null)),
                ReportErrorCode.INVALID_EVIDENCE_MESSAGE);
        assertThat(reportRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("RPT-C04 같은 대상에 다른 사유로 추가 신고하면 별도 행으로 저장된다")
    void differentReasonMakesSecondRow() {
        reportCommandService.receive(reporter.getId(), listingRequest(listing, ReportReason.FRAUD, null));
        reportCommandService.receive(reporter.getId(), listingRequest(listing, ReportReason.SPAM, null));

        assertThat(reportRepository.findAll()).extracting(Report::getReasonCode)
                .containsExactlyInAnyOrder(ReportReason.FRAUD, ReportReason.SPAM);
    }

    @Test
    @DisplayName("RPT-C05 같은 매물·같은 사유라도 신고자가 다르면 각각 접수된다")
    void differentReportersEachSucceed() {
        User another = user();

        reportCommandService.receive(reporter.getId(), listingRequest(listing, ReportReason.FRAUD, null));
        reportCommandService.receive(another.getId(), listingRequest(listing, ReportReason.FRAUD, null));

        assertThat(reportRepository.findAll()).extracting(Report::getReporterId)
                .containsExactlyInAnyOrder(reporter.getId(), another.getId());
    }

    @Test
    @DisplayName("RPT-C10 참여하지 않은 채팅방은 실제 조회에서도 404(C002)")
    void nonParticipantCannotReportRoom() {
        User buyer = user();
        ChatRoom room = room(listing, buyer);
        Long evidence = message(room, seller);

        assertErrorCode(() -> reportCommandService.receive(reporter.getId(), chatRequest(room, evidence, null)),
                CommonErrorCode.RESOURCE_NOT_FOUND);
        assertThat(reportRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("RPT-C34 CLK-08 취소한 신고를 같은 사유로 다시 내면 같은 행이 RECEIVED로 살아나고 detail·evidence·receivedAt만 바뀐다")
    void cancelledReportIsRevivedInPlace() {
        ChatRoom room = room(listing, reporter);
        Long firstEvidence = message(room, seller);
        Long secondEvidence = message(room, seller);
        ReportCreateResponse first = reportCommandService.receive(reporter.getId(), chatRequest(room, firstEvidence, "처음"));
        reportCommandService.cancel(reporter.getId(), first.reportId());
        // 시각을 과거로 밀어 둬야 "갱신됨/유지됨"을 같은 밀리초 우연 없이 가를 수 있다.
        // 문자열 리터럴인 이유: hibernate.jdbc.time_zone=UTC라 DB 값은 UTC 기준이다(Timestamp는 JVM 시간대를 탄다).
        Instant past = Instant.parse("2026-01-01T00:00:00Z");
        jdbcTemplate.update("UPDATE reports SET created_at = '2026-01-01 00:00:00', "
                + "received_at = '2026-01-01 00:00:00' WHERE public_id = ?", first.reportId());
        Long originalId = onlyReport().getId();
        clock.advance(Duration.ofHours(1)); // CLK-08: 부활 시각을 테스트가 정한다
        Instant revivedAt = clock.instant();

        ReportCreateResponse revived = reportCommandService.receive(
                reporter.getId(), chatRequest(room, secondEvidence, "다시"));

        Report row = onlyReport();
        assertThat(row.getId()).isEqualTo(originalId);
        assertThat(row.getPublicId()).isEqualTo(first.reportId());
        assertThat(revived.reportId()).isEqualTo(first.reportId());
        assertThat(row.getStatus()).isEqualTo(ReportStatus.RECEIVED);
        assertThat(row.getDetail()).isEqualTo("다시");
        assertThat(row.getEvidenceMessageId()).isEqualTo(secondEvidence);
        assertThat(row.getReceivedAt()).isAfter(past);
        assertThat(row.getReceivedAt()).isEqualTo(revivedAt);
        assertThat(revived.receivedAt()).isEqualTo(revivedAt);
        assertThat(row.getCreatedAt()).isEqualTo(past);
    }

    @Test
    @DisplayName("CLK-06 RPT-C26 한국 23:59:59.999에 하루 10건을 다 쓴 뒤 시계가 다음 날 00:00으로 넘어가면 새 신고가 접수되고 새 날짜로 센다")
    void dailyLimitResetsAtKstMidnight() {
        clock.set(Instant.parse("2026-11-15T23:59:59.999+09:00"));
        for (int i = 0; i < 10; i++) {
            assertThat(dailyLimiter.tryAcquire(reporter.getId(), clock.instant())).isTrue();
        }
        Instant nextKstMidnight = Instant.parse("2026-11-16T00:00:00+09:00");
        clock.set(nextKstMidnight);

        ReportCreateResponse response = reportCommandService.receive(
                reporter.getId(), listingRequest(listing, ReportReason.FRAUD, null));

        assertThat(response.status()).isEqualTo(ReportStatus.RECEIVED);
        assertThat(response.receivedAt()).isEqualTo(nextKstMidnight);
        assertThat(onlyReport().getReceivedAt()).isEqualTo(nextKstMidnight);
        assertThat(redisTemplate.opsForValue().get("rate:v1:" + reporter.getId() + ":report:20261116"))
                .as("다음 날 키로 1건").isEqualTo("1");
    }

    @Test
    @DisplayName("RPT-C35 부활 때 상세 내용을 보내지 않으면 예전 값이 남지 않고 null로 덮어쓴다 (Q8)")
    void revivalClearsDetail() {
        ReportCreateResponse first = reportCommandService.receive(
                reporter.getId(), listingRequest(listing, ReportReason.FRAUD, "예전 설명"));
        reportCommandService.cancel(reporter.getId(), first.reportId());

        reportCommandService.receive(reporter.getId(), listingRequest(listing, ReportReason.FRAUD, null));

        Report row = onlyReport();
        assertThat(row.getStatus()).isEqualTo(ReportStatus.RECEIVED);
        assertThat(row.getDetail()).isNull();
        assertThat(row.getEvidenceMessageId()).isNull();
    }

    @Test
    @DisplayName("RPT-C36 같은 요청을 연달아 두 번 보내면 첫 번째만 접수되고 두 번째는 409(RPT001)")
    void sequentialDuplicateIsRejected() {
        reportCommandService.receive(reporter.getId(), listingRequest(listing, ReportReason.FRAUD, null));

        assertErrorCode(() -> reportCommandService.receive(
                        reporter.getId(), listingRequest(listing, ReportReason.FRAUD, null)),
                ReportErrorCode.DUPLICATE_ACTIVE_REPORT);
        assertThat(reportRepository.findAll()).hasSize(1);
    }
}
