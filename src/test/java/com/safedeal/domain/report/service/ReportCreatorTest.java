package com.safedeal.domain.report.service;

import com.safedeal.domain.auth.exception.AuthErrorCode;
import com.safedeal.domain.chat.entity.ChatRoom;
import com.safedeal.domain.chat.repository.ChatMessageRepository;
import com.safedeal.domain.chat.repository.ChatRoomRepository;
import com.safedeal.domain.listing.entity.Category;
import com.safedeal.domain.listing.entity.ItemCondition;
import com.safedeal.domain.listing.entity.Listing;
import com.safedeal.domain.listing.entity.ListingStatus;
import com.safedeal.domain.listing.repository.ListingRepository;
import com.safedeal.domain.report.dto.ReportCreateRequest;
import com.safedeal.domain.report.dto.ReportCreateResponse;
import com.safedeal.domain.report.entity.Report;
import com.safedeal.domain.report.entity.ReportReason;
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
import com.safedeal.global.exception.ErrorCode;
import com.safedeal.global.util.PublicIdGenerator;
import com.safedeal.testsupport.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 신고 접수 한 번의 시도(트랜잭션 경계) — POST /api/reports. 리포지토리·한도 카운터는 목이다.
 *
 * <p>기본 상황({@link #setUp()}): 신고자 20은 정상 계정이고, 판매자 30의 ACTIVE 매물(10)과
 * 신고자가 구매자로 참여한 채팅방(77)이 있으며, 그 방에 판매자가 보낸 메시지(500)가 있다.
 * 같은 사유의 기존 신고는 없고, 하루 한도는 남아 있다.
 *
 * <p>거절 케이스는 전부 {@link #assertRejected}로 확인한다 — 에러 코드와 함께 "저장·부활을 하지 않았고
 * 하루 한도도 차감하지 않았다"(RPT-C42, Q2)까지 본다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportCreatorTest {

    @Mock ReportRepository reportRepository;
    @Mock ListingRepository listingRepository;
    @Mock ChatRoomRepository chatRoomRepository;
    @Mock ChatMessageRepository chatMessageRepository;
    @Mock UserRepository userRepository;
    @Mock ReportDailyLimiter dailyLimiter;
    @Mock PublicIdGenerator publicIdGenerator;
    /** 서비스가 읽는 시계. 기본값 {@link MutableClock#DEFAULT}에 멈춰 있어 시각을 정확히 단언할 수 있다(CLK-02·CLK-03). */
    @Spy MutableClock clock = new MutableClock();

    @InjectMocks ReportCreator reportCreator;

    private static final Long REPORTER = 20L;
    private static final Long SELLER = 30L;
    private static final Long STRANGER = 40L;
    private static final Long LISTING_ID = 10L;
    private static final Long ROOM_ID = 77L;
    private static final Long EVIDENCE = 500L;
    private static final Long EXISTING_REPORT_ID = 99L;
    private static final String LISTING_PUBLIC_ID = "01J3ARSNIPLISTING000000001";
    private static final String ROOM_PUBLIC_ID = "01J3ARSNIPROOM000000000001";
    private static final String NEW_REPORT_PUBLIC_ID = "01J3ARSNIPREPORT0000000002";
    private static final String EXISTING_REPORT_PUBLIC_ID = "01J3ARSNIPREPORT0000000001";

    @BeforeEach
    void setUp() {
        givenUser(REPORTER, UserStatus.ACTIVE, false);
        givenUser(SELLER, UserStatus.ACTIVE, false);
        givenListing(listing(ListingStatus.ACTIVE));
        ChatRoom room = ChatRoom.open(ROOM_PUBLIC_ID, LISTING_ID, REPORTER, SELLER);
        ReflectionTestUtils.setField(room, "id", ROOM_ID);
        when(chatRoomRepository.findByPublicIdAndParticipant(ROOM_PUBLIC_ID, REPORTER)).thenReturn(Optional.of(room));
        when(chatRoomRepository.findByPublicIdAndParticipant(ROOM_PUBLIC_ID, SELLER)).thenReturn(Optional.of(room));
        when(chatMessageRepository.existsByIdAndRoomIdAndSenderId(EVIDENCE, ROOM_ID, SELLER)).thenReturn(true);
        when(dailyLimiter.tryAcquire(any(), any())).thenReturn(true);
        when(publicIdGenerator.generate()).thenReturn(NEW_REPORT_PUBLIC_ID);
        when(reportRepository.save(any(Report.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ── 픽스처 ──────────────────────────────────────────────

    /** 계정 조회 방법(삭제 포함/제외)에 상관없이 같은 상태가 보이도록 둘 다 맞춰 둔다. */
    private void givenUser(Long id, UserStatus status, boolean withdrawn) {
        User user = User.createLocal("u" + id + "@safedeal.test", "hash", "user" + id);
        ReflectionTestUtils.setField(user, "id", id);
        ReflectionTestUtils.setField(user, "status", status);
        if (withdrawn) {
            ReflectionTestUtils.setField(user, "deletedAt", Instant.parse("2026-11-01T00:00:00Z"));
        }
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        when(userRepository.findByIdAndDeletedAtIsNull(id)).thenReturn(withdrawn ? Optional.empty() : Optional.of(user));
    }

    private static Listing listing(ListingStatus status) {
        Category phone = Category.child("DIGITAL_PHONE", "휴대폰", Category.root("DIGITAL", "디지털", 1), 1);
        Listing listing = Listing.register(LISTING_PUBLIC_ID, SELLER, "아이폰", "설명", 500_000,
                phone, ItemCondition.USED, "서울특별시", "강남구", false);
        ReflectionTestUtils.setField(listing, "id", LISTING_ID);
        ReflectionTestUtils.setField(listing, "status", status);
        return listing;
    }

    /** 매물 조회 방법(삭제 포함/제외)에 상관없이 같은 매물이 보이도록 맞춰 둔다. */
    private void givenListing(Listing listing) {
        when(listingRepository.findByPublicId(LISTING_PUBLIC_ID)).thenReturn(Optional.of(listing));
        when(listingRepository.findByPublicIdIncludingDeleted(LISTING_PUBLIC_ID)).thenReturn(Optional.of(listing));
        when(listingRepository.findByPublicIdAndDeletedAtIsNull(LISTING_PUBLIC_ID))
                .thenReturn(listing.isDeleted() ? Optional.empty() : Optional.of(listing));
    }

    /**
     * 같은 신고자·매물·사유(FRAUD)의 기존 신고.
     *
     * <p>부활 전 잠금 재조회는 {@code refreshForUpdate}(행 잠금 + DB 값으로 새로 읽기, RPT-C51)가 같은 행을
     * 돌려주게 둔다. 호출 여부는 검증하지 않는다 — 잠금이 실제로 직렬화하고 옛 값을 쓰지 않는지는
     * {@code ReportCreateConcurrencyTest}가 실제 DB로 증명한다.
     */
    private Report givenExisting(ReportStatus status) {
        Report existing = existing(status);
        when(reportRepository.findByReporterIdAndTargetTypeAndTargetIdAndReasonCode(
                REPORTER, ReportTargetType.LISTING, LISTING_ID, ReportReason.FRAUD)).thenReturn(Optional.of(existing));
        givenLockedView(existing);
        return existing;
    }

    /** 잠금 재조회 결과. 구현은 {@code refreshForUpdate}의 반환값으로 판정해야 한다. */
    private void givenLockedView(Report report) {
        when(reportRepository.refreshForUpdate(any(Report.class))).thenReturn(report);
    }

    private static Report existing(ReportStatus status) {
        Report report = Report.receive(EXISTING_REPORT_PUBLIC_ID, REPORTER, ReportTargetType.LISTING, LISTING_ID,
                LISTING_PUBLIC_ID, SELLER, ReportReason.FRAUD, "예전 설명", null,
                Instant.parse("2026-11-01T00:00:00Z"));
        ReflectionTestUtils.setField(report, "id", EXISTING_REPORT_ID);
        ReflectionTestUtils.setField(report, "status", status);
        return report;
    }

    private static ReportCreateRequest listingRequest(ReportReason reason, String detail) {
        return new ReportCreateRequest(ReportTargetType.LISTING, LISTING_PUBLIC_ID, reason, detail, null);
    }

    private static ReportCreateRequest chatRequest(ReportReason reason, Long evidenceMessageId) {
        return new ReportCreateRequest(ReportTargetType.CHAT, ROOM_PUBLIC_ID, reason, "욕설을 했습니다", evidenceMessageId);
    }

    private Report savedReport() {
        ArgumentCaptor<Report> captor = ArgumentCaptor.forClass(Report.class);
        verify(reportRepository).save(captor.capture());
        return captor.getValue();
    }

    private void assertRejected(Long reporterId, ReportCreateRequest request, ErrorCode expected) {
        assertThatThrownBy(() -> reportCreator.receive(reporterId, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(expected);
        verify(reportRepository, never()).save(any());
        verify(reportRepository, never()).reviveCancelled(any(), any(), any(), any());
        verify(dailyLimiter, never()).tryAcquire(any(), any());
    }

    private void assertRejected(ReportCreateRequest request, ErrorCode expected) {
        assertRejected(REPORTER, request, expected);
    }

    // ── 정상 ──────────────────────────────────────────────

    @Test
    @DisplayName("RPT-C01 남의 ACTIVE 매물을 사기로 신고하면 RECEIVED로 저장되고 피신고자는 판매자다")
    void listingReportIsSaved() {
        ReportCreateResponse response = reportCreator.receive(REPORTER, listingRequest(ReportReason.FRAUD, "허위 매물"));

        Report saved = savedReport();
        assertThat(saved.getPublicId()).isEqualTo(NEW_REPORT_PUBLIC_ID);
        assertThat(saved.getReporterId()).isEqualTo(REPORTER);
        assertThat(saved.getTargetType()).isEqualTo(ReportTargetType.LISTING);
        assertThat(saved.getTargetId()).isEqualTo(LISTING_ID);
        assertThat(saved.getTargetPublicId()).isEqualTo(LISTING_PUBLIC_ID);
        assertThat(saved.getAccusedUserId()).isEqualTo(SELLER);
        assertThat(saved.getReasonCode()).isEqualTo(ReportReason.FRAUD);
        assertThat(saved.getDetail()).isEqualTo("허위 매물");
        assertThat(saved.getEvidenceMessageId()).isNull();
        assertThat(response).isEqualTo(new ReportCreateResponse(
                NEW_REPORT_PUBLIC_ID, ReportStatus.RECEIVED, saved.getReceivedAt()));
        verify(dailyLimiter).tryAcquire(eq(REPORTER), any(Instant.class));
    }

    @Test
    @DisplayName("CLK-02 새 신고의 한도 시각·저장된 receivedAt·응답 시각은 모두 주입된 시계의 값이다")
    void newReportUsesInjectedClock() {
        ReportCreateResponse response = reportCreator.receive(REPORTER, listingRequest(ReportReason.FRAUD, null));

        verify(dailyLimiter).tryAcquire(REPORTER, MutableClock.DEFAULT);
        assertThat(savedReport().getReceivedAt()).isEqualTo(MutableClock.DEFAULT);
        assertThat(response.receivedAt()).isEqualTo(MutableClock.DEFAULT);
    }

    @Test
    @DisplayName("RPT-C02 구매자가 채팅방을 욕설로 신고하면 피신고자는 방 상대(판매자)이고 증거 메시지가 저장된다")
    void chatReportByBuyerAccusesSeller() {
        reportCreator.receive(REPORTER, chatRequest(ReportReason.ABUSE, EVIDENCE));

        Report saved = savedReport();
        assertThat(saved.getTargetType()).isEqualTo(ReportTargetType.CHAT);
        assertThat(saved.getTargetId()).isEqualTo(ROOM_ID);
        assertThat(saved.getTargetPublicId()).isEqualTo(ROOM_PUBLIC_ID);
        assertThat(saved.getAccusedUserId()).isEqualTo(SELLER);
        assertThat(saved.getEvidenceMessageId()).isEqualTo(EVIDENCE);
        assertThat(saved.getStatus()).isEqualTo(ReportStatus.RECEIVED);
    }

    @Test
    @DisplayName("RPT-C02 판매자가 채팅방을 신고하면 피신고자는 구매자다")
    void chatReportBySellerAccusesBuyer() {
        when(chatMessageRepository.existsByIdAndRoomIdAndSenderId(600L, ROOM_ID, REPORTER)).thenReturn(true);

        reportCreator.receive(SELLER, chatRequest(ReportReason.ABUSE, 600L));

        assertThat(savedReport().getAccusedUserId()).isEqualTo(REPORTER);
    }

    @Test
    @DisplayName("RPT-C03 판매 완료(SOLD) 매물도 신고할 수 있다")
    void soldListingCanBeReported() {
        givenListing(listing(ListingStatus.SOLD));

        reportCreator.receive(REPORTER, listingRequest(ReportReason.FRAUD, null));

        assertThat(savedReport().getTargetId()).isEqualTo(LISTING_ID);
    }

    @Test
    @DisplayName("RPT-C04 같은 대상에 다른 사유의 신고가 이미 있어도 새 사유는 별도 행으로 저장된다")
    void differentReasonIsSavedAsNewRow() {
        givenExisting(ReportStatus.RECEIVED); // FRAUD

        reportCreator.receive(REPORTER, listingRequest(ReportReason.SPAM, null));

        assertThat(savedReport().getReasonCode()).isEqualTo(ReportReason.SPAM);
    }

    @Test
    @DisplayName("RPT-C02 숨긴(나간) 채팅방이라도 참여자면 신고할 수 있다 (Q12)")
    void hiddenRoomCanBeReported() {
        ChatRoom hidden = ChatRoom.open(ROOM_PUBLIC_ID, LISTING_ID, REPORTER, SELLER);
        ReflectionTestUtils.setField(hidden, "id", ROOM_ID);
        ReflectionTestUtils.setField(hidden, "buyerHiddenAt", Instant.parse("2026-11-10T00:00:00Z"));
        when(chatRoomRepository.findByPublicIdAndParticipant(ROOM_PUBLIC_ID, REPORTER)).thenReturn(Optional.of(hidden));

        reportCreator.receive(REPORTER, chatRequest(ReportReason.ABUSE, EVIDENCE));

        assertThat(savedReport().getTargetId()).isEqualTo(ROOM_ID);
    }

    // ── 대상 검증 ──────────────────────────────────────────────

    @Test
    @DisplayName("RPT-C06 본인 매물은 신고할 수 없다 - 400(RPT006)")
    void ownListingIsRejected() {
        assertRejected(SELLER, listingRequest(ReportReason.FRAUD, null), ReportErrorCode.SELF_REPORT_NOT_ALLOWED);
    }

    @Test
    @DisplayName("RPT-C50 본인의 공개 전(DRAFT·PENDING_VERIFICATION) 매물을 신고하면 404가 아니라 400(RPT006)")
    void ownUnpublishedListingIsSelfReport() {
        for (ListingStatus status : new ListingStatus[] {ListingStatus.DRAFT, ListingStatus.PENDING_VERIFICATION}) {
            givenListing(listing(status));

            assertRejected(SELLER, listingRequest(ReportReason.FRAUD, null), ReportErrorCode.SELF_REPORT_NOT_ALLOWED);
        }
    }

    @Test
    @DisplayName("RPT-C07 없는 매물 ULID면 404(C002)")
    void missingListingIsNotFound() {
        ReportCreateRequest request = new ReportCreateRequest(
                ReportTargetType.LISTING, "01J3ARSNIPLISTING000000099", ReportReason.FRAUD, null, null);

        assertRejected(request, CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    @DisplayName("RPT-C07 없는 채팅방 ULID면 404(C002)")
    void missingRoomIsNotFound() {
        ReportCreateRequest request = new ReportCreateRequest(
                ReportTargetType.CHAT, "01J3ARSNIPROOM000000000099", ReportReason.ABUSE, null, EVIDENCE);

        assertRejected(request, CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    @DisplayName("RPT-C08 삭제된 매물이면 404(C002)")
    void deletedListingIsNotFound() {
        Listing deleted = listing(ListingStatus.DELETED);
        ReflectionTestUtils.setField(deleted, "deletedAt", Instant.parse("2026-11-01T00:00:00Z"));
        givenListing(deleted);

        assertRejected(listingRequest(ReportReason.FRAUD, null), CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    @DisplayName("RPT-C09 공개 전(DRAFT) 매물이면 404(C002) (Q3)")
    void draftListingIsNotFound() {
        givenListing(listing(ListingStatus.DRAFT));

        assertRejected(listingRequest(ReportReason.FRAUD, null), CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    @DisplayName("RPT-C09 검증 대기(PENDING_VERIFICATION) 매물이면 404(C002) (Q3)")
    void pendingListingIsNotFound() {
        givenListing(listing(ListingStatus.PENDING_VERIFICATION));

        assertRejected(listingRequest(ReportReason.FRAUD, null), CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    @DisplayName("RPT-C09 이미 제재된(BLOCKED) 매물이면 409(RPT007) (Q3)")
    void blockedListingIsAlreadySanctioned() {
        givenListing(listing(ListingStatus.BLOCKED));

        assertRejected(listingRequest(ReportReason.FRAUD, null), ReportErrorCode.TARGET_ALREADY_SANCTIONED);
    }

    @Test
    @DisplayName("RPT-C10 참여하지 않은 채팅방을 신고하면 404(C002) - 방 존재를 알려주지 않는다")
    void nonParticipantIsNotFound() {
        givenUser(STRANGER, UserStatus.ACTIVE, false);

        assertRejected(STRANGER, chatRequest(ReportReason.ABUSE, EVIDENCE), CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    @DisplayName("RPT-C18 채팅 상대가 정지(SUSPENDED)됐으면 409(RPT007)")
    void suspendedChatPartnerIsAlreadySanctioned() {
        givenUser(SELLER, UserStatus.SUSPENDED, false);

        assertRejected(chatRequest(ReportReason.ABUSE, EVIDENCE), ReportErrorCode.TARGET_ALREADY_SANCTIONED);
    }

    @Test
    @DisplayName("RPT-C18 채팅 상대가 영구 정지(BANNED)됐으면 409(RPT007)")
    void bannedChatPartnerIsAlreadySanctioned() {
        givenUser(SELLER, UserStatus.BANNED, false);

        assertRejected(chatRequest(ReportReason.ABUSE, EVIDENCE), ReportErrorCode.TARGET_ALREADY_SANCTIONED);
    }

    @Test
    @DisplayName("RPT-C18 반대 방향: 판매자가 신고했는데 방 상대(구매자)가 정지됐으면 409(RPT007)")
    void suspendedBuyerReportedBySellerIsAlreadySanctioned() {
        when(chatMessageRepository.existsByIdAndRoomIdAndSenderId(600L, ROOM_ID, REPORTER)).thenReturn(true);
        givenUser(REPORTER, UserStatus.SUSPENDED, false); // 이 테스트에서 20은 신고자가 아니라 피신고자(구매자)

        assertRejected(SELLER, chatRequest(ReportReason.ABUSE, 600L), ReportErrorCode.TARGET_ALREADY_SANCTIONED);
    }

    @Test
    @DisplayName("RPT-C18 반대 방향: 판매자가 신고했는데 방 상대(구매자)가 영구 정지됐으면 409(RPT007)")
    void bannedBuyerReportedBySellerIsAlreadySanctioned() {
        when(chatMessageRepository.existsByIdAndRoomIdAndSenderId(600L, ROOM_ID, REPORTER)).thenReturn(true);
        givenUser(REPORTER, UserStatus.BANNED, false);

        assertRejected(SELLER, chatRequest(ReportReason.ABUSE, 600L), ReportErrorCode.TARGET_ALREADY_SANCTIONED);
    }

    @Test
    @DisplayName("RPT-C46 채팅 상대가 탈퇴했으면 404(C002) (D2)")
    void withdrawnChatPartnerIsNotFound() {
        givenUser(SELLER, UserStatus.ACTIVE, true);

        assertRejected(chatRequest(ReportReason.ABUSE, EVIDENCE), CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    @DisplayName("RPT-C47 본인의 BLOCKED 매물을 신고하면 RPT007이 아니라 400(RPT006) (D3)")
    void ownBlockedListingIsSelfReport() {
        givenListing(listing(ListingStatus.BLOCKED));

        assertRejected(SELLER, listingRequest(ReportReason.FRAUD, null), ReportErrorCode.SELF_REPORT_NOT_ALLOWED);
    }

    @Test
    @DisplayName("RPT-C19 매물 판매자가 정지(SUSPENDED)됐으면 409(RPT007) (Q4)")
    void suspendedSellerIsAlreadySanctioned() {
        givenUser(SELLER, UserStatus.SUSPENDED, false);

        assertRejected(listingRequest(ReportReason.FRAUD, null), ReportErrorCode.TARGET_ALREADY_SANCTIONED);
    }

    @Test
    @DisplayName("RPT-C19 매물 판매자가 영구 정지(BANNED)됐으면 409(RPT007) (Q4)")
    void bannedSellerIsAlreadySanctioned() {
        givenUser(SELLER, UserStatus.BANNED, false);

        assertRejected(listingRequest(ReportReason.FRAUD, null), ReportErrorCode.TARGET_ALREADY_SANCTIONED);
    }

    @Test
    @DisplayName("RPT-C19 매물 판매자가 탈퇴했으면 404(C002) (Q4)")
    void withdrawnSellerIsNotFound() {
        givenUser(SELLER, UserStatus.ACTIVE, true);

        assertRejected(listingRequest(ReportReason.FRAUD, null), CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    // ── 증거 메시지·사유 ──────────────────────────────────────────────

    @Test
    @DisplayName("RPT-C11 채팅 신고에 증거 메시지 id가 없으면 400(RPT005)")
    void chatWithoutEvidenceIsRejected() {
        assertRejected(chatRequest(ReportReason.ABUSE, null), ReportErrorCode.INVALID_EVIDENCE_MESSAGE);
    }

    @Test
    @DisplayName("RPT-C12 증거 메시지가 다른 방 소속이면 400(RPT005)")
    void evidenceFromOtherRoomIsRejected() {
        // 목으로는 "다른 방에 있다"를 표현할 수 없다 — 이 방(77) 기준 조회가 없다고 답하는 것만 둔다.
        // 실제 방 불일치는 ChatMessageEvidenceLookupTest(쿼리)와 ReportCreateIntegrationTest(끝까지)가 재현한다.
        assertRejected(chatRequest(ReportReason.ABUSE, 700L), ReportErrorCode.INVALID_EVIDENCE_MESSAGE);
    }

    @Test
    @DisplayName("RPT-C13 증거 메시지가 신고자 본인이 보낸 것이면 400(RPT005)")
    void ownMessageAsEvidenceIsRejected() {
        when(chatMessageRepository.existsByIdAndRoomIdAndSenderId(600L, ROOM_ID, REPORTER)).thenReturn(true);

        assertRejected(chatRequest(ReportReason.ABUSE, 600L), ReportErrorCode.INVALID_EVIDENCE_MESSAGE);
    }

    @Test
    @DisplayName("RPT-C14 없는 메시지 id면 400(RPT005)")
    void missingEvidenceIsRejected() {
        assertRejected(chatRequest(ReportReason.ABUSE, 999_999L), ReportErrorCode.INVALID_EVIDENCE_MESSAGE);
    }

    @Test
    @DisplayName("RPT-C15 매물 신고에 증거 메시지 id가 있으면 400(C001) (Q5)")
    void listingWithEvidenceIsRejected() {
        ReportCreateRequest request = new ReportCreateRequest(
                ReportTargetType.LISTING, LISTING_PUBLIC_ID, ReportReason.FRAUD, null, EVIDENCE);

        assertRejected(request, CommonErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("RPT-C16 사기 사유로 채팅방을 신고하면 400(RPT004)")
    void fraudOnChatIsRejected() {
        assertRejected(chatRequest(ReportReason.FRAUD, EVIDENCE), ReportErrorCode.REASON_NOT_ALLOWED_FOR_TARGET);
    }

    @Test
    @DisplayName("RPT-C16 욕설 사유로 매물을 신고하면 400(RPT004)")
    void abuseOnListingIsRejected() {
        assertRejected(listingRequest(ReportReason.ABUSE, null), ReportErrorCode.REASON_NOT_ALLOWED_FOR_TARGET);
    }

    // ── 상세 내용 경계 ──────────────────────────────────────────────

    @Test
    @DisplayName("RPT-C20 상세 내용이 정확히 1,000자면 그대로 저장된다")
    void detailOf1000IsSaved() {
        reportCreator.receive(REPORTER, listingRequest(ReportReason.FRAUD, "가".repeat(1000)));

        assertThat(savedReport().getDetail()).isEqualTo("가".repeat(1000));
    }

    @Test
    @DisplayName("RPT-C22 상세 내용이 없으면(null) null로 저장된다")
    void nullDetailIsSaved() {
        reportCreator.receive(REPORTER, listingRequest(ReportReason.FRAUD, null));

        assertThat(savedReport().getDetail()).isNull();
    }

    @Test
    @DisplayName("RPT-C23 상세 내용이 공백뿐이면 null로 저장된다 (Q6)")
    void blankDetailIsSavedAsNull() {
        reportCreator.receive(REPORTER, listingRequest(ReportReason.FRAUD, "   "));

        assertThat(savedReport().getDetail()).isNull();
    }

    @Test
    @DisplayName("RPT-C48 글자가 있는 상세 내용은 앞뒤 공백까지 원문 그대로 저장된다 - strip하지 않는다 (D4)")
    void nonBlankDetailIsSavedAsIs() {
        reportCreator.receive(REPORTER, listingRequest(ReportReason.FRAUD, "  허위 매물\n"));

        assertThat(savedReport().getDetail()).isEqualTo("  허위 매물\n");
    }

    @Test
    @DisplayName("RPT-C48 부활 때도 글자가 있는 상세 내용은 원문 그대로 덮어쓴다 (D4·Q8)")
    void revivalKeepsNonBlankDetailAsIs() {
        givenExisting(ReportStatus.CANCELLED);
        when(reportRepository.reviveCancelled(any(), any(), any(), any())).thenReturn(1);

        reportCreator.receive(REPORTER, listingRequest(ReportReason.FRAUD, " 다시 신고 "));

        verify(reportRepository).reviveCancelled(eq(EXISTING_REPORT_ID), eq(" 다시 신고 "), isNull(), any(Instant.class));
    }

    // ── 하루 한도 ──────────────────────────────────────────────

    @Test
    @DisplayName("RPT-C24 한도 카운터가 통과시키면(10번째까지) 저장된다")
    void withinDailyLimitIsSaved() {
        when(dailyLimiter.tryAcquire(eq(REPORTER), any(Instant.class))).thenReturn(true);

        reportCreator.receive(REPORTER, listingRequest(ReportReason.FRAUD, null));

        assertThat(savedReport().getReporterId()).isEqualTo(REPORTER);
    }

    @Test
    @DisplayName("RPT-C25 한도 카운터가 막으면(11번째) 429(RPT003)이고 저장하지 않는다")
    void overDailyLimitIsRejected() {
        when(dailyLimiter.tryAcquire(eq(REPORTER), any(Instant.class))).thenReturn(false);

        assertThatThrownBy(() -> reportCreator.receive(REPORTER, listingRequest(ReportReason.FRAUD, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReportErrorCode.DAILY_REPORT_LIMIT_EXCEEDED);
        verify(reportRepository, never()).save(any());
    }

    @Test
    @DisplayName("RPT-C42 검증에 실패한 시도는 하루 한도를 차감하지 않는다 - 한도는 모든 검증·중복 판정 뒤에 센다 (Q2)")
    void failedAttemptsDoNotConsumeDailyLimit() {
        assertThatThrownBy(() -> reportCreator.receive(REPORTER, chatRequest(ReportReason.FRAUD, EVIDENCE)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> reportCreator.receive(REPORTER, chatRequest(ReportReason.ABUSE, 999_999L)))
                .isInstanceOf(BusinessException.class);
        givenExisting(ReportStatus.RECEIVED);
        assertThatThrownBy(() -> reportCreator.receive(REPORTER, listingRequest(ReportReason.FRAUD, null)))
                .isInstanceOf(BusinessException.class);

        verify(dailyLimiter, never()).tryAcquire(any(), any());
    }

    // ── 신고자 권한 ──────────────────────────────────────────────

    @Test
    @DisplayName("RPT-C29 정지(SUSPENDED)된 신고자는 403(C006) (Q7)")
    void suspendedReporterIsForbidden() {
        givenUser(REPORTER, UserStatus.SUSPENDED, false);

        assertRejected(listingRequest(ReportReason.FRAUD, null), CommonErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("RPT-C29 영구 정지(BANNED)된 신고자는 403(C006) (Q7)")
    void bannedReporterIsForbidden() {
        givenUser(REPORTER, UserStatus.BANNED, false);

        assertRejected(listingRequest(ReportReason.FRAUD, null), CommonErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("RPT-C49 신고자 계정이 없으면(탈퇴 직후 토큰) 401(AUTH004 INVALID_TOKEN) (D5)")
    void missingReporterIsInvalidToken() {
        givenUser(REPORTER, UserStatus.ACTIVE, true);

        assertRejected(listingRequest(ReportReason.FRAUD, null), AuthErrorCode.INVALID_TOKEN);
    }

    // ── 중복·부활 ──────────────────────────────────────────────

    @Test
    @DisplayName("RPT-C31 같은 사유의 신고가 접수(RECEIVED) 상태면 409(RPT001)")
    void receivedDuplicateIsRejected() {
        givenExisting(ReportStatus.RECEIVED);

        assertRejected(listingRequest(ReportReason.FRAUD, null), ReportErrorCode.DUPLICATE_ACTIVE_REPORT);
    }

    @Test
    @DisplayName("RPT-C32 같은 사유의 신고가 검토 중(REVIEWING)이면 409(RPT001)")
    void reviewingDuplicateIsRejected() {
        givenExisting(ReportStatus.REVIEWING);

        assertRejected(listingRequest(ReportReason.FRAUD, null), ReportErrorCode.DUPLICATE_ACTIVE_REPORT);
    }

    @Test
    @DisplayName("RPT-C33 같은 사유의 신고가 처리(RESOLVED)됐으면 409(RPT002)")
    void resolvedDuplicateIsRejected() {
        givenExisting(ReportStatus.RESOLVED);

        assertRejected(listingRequest(ReportReason.FRAUD, null), ReportErrorCode.REPORT_ALREADY_CLOSED);
    }

    @Test
    @DisplayName("RPT-C33 같은 사유의 신고가 기각(REJECTED)됐으면 409(RPT002)")
    void rejectedDuplicateIsRejected() {
        givenExisting(ReportStatus.REJECTED);

        assertRejected(listingRequest(ReportReason.FRAUD, null), ReportErrorCode.REPORT_ALREADY_CLOSED);
    }

    @Test
    @DisplayName("RPT-C34 같은 사유의 취소된 신고가 있으면 새 행 없이 그 행을 조건부 UPDATE로 되살리고 같은 reportId로 응답한다")
    void cancelledReportIsRevived() {
        Instant previousReceivedAt = givenExisting(ReportStatus.CANCELLED).getReceivedAt();
        when(reportRepository.reviveCancelled(eq(EXISTING_REPORT_ID), eq("다시 신고"), isNull(), any(Instant.class)))
                .thenReturn(1);

        ReportCreateResponse response = reportCreator.receive(REPORTER, listingRequest(ReportReason.FRAUD, "다시 신고"));

        ArgumentCaptor<Instant> now = ArgumentCaptor.forClass(Instant.class);
        verify(reportRepository).reviveCancelled(eq(EXISTING_REPORT_ID), eq("다시 신고"), isNull(), now.capture());
        assertThat(now.getValue()).isAfter(previousReceivedAt); // 접수 시각을 새로 찍는다(큐 정렬 기준)
        verify(reportRepository, never()).save(any());
        assertThat(response).isEqualTo(new ReportCreateResponse(
                EXISTING_REPORT_PUBLIC_ID, ReportStatus.RECEIVED, now.getValue()));
        verify(dailyLimiter).tryAcquire(eq(REPORTER), any(Instant.class));
    }

    @Test
    @DisplayName("RPT-C35 부활 때 상세 내용을 비우면(공백) 예전 값이 아니라 null로 덮어쓴다 (Q6·Q8)")
    void revivalOverwritesDetailWithNull() {
        givenExisting(ReportStatus.CANCELLED);
        when(reportRepository.reviveCancelled(eq(EXISTING_REPORT_ID), isNull(), isNull(), any(Instant.class)))
                .thenReturn(1);

        reportCreator.receive(REPORTER, listingRequest(ReportReason.FRAUD, "  "));

        verify(reportRepository).reviveCancelled(eq(EXISTING_REPORT_ID), isNull(), isNull(), any(Instant.class));
    }

    @Test
    @DisplayName("RPT-C52 CLK-03 부활의 한도 차감·저장·응답 시각은 잠금 전이 아니라 잠금을 얻은 뒤의 시계 값이다 (QC-4)")
    void revivalUsesTimeAfterLockIsAcquired() {
        Report existing = givenExisting(ReportStatus.CANCELLED);
        Instant lockAcquiredAt = MutableClock.DEFAULT.plusSeconds(5);
        when(reportRepository.refreshForUpdate(any(Report.class))).thenAnswer(invocation -> {
            clock.advance(Duration.ofSeconds(5)); // 잠금을 기다리는 사이 시간이 흐른다
            return existing;
        });
        when(reportRepository.reviveCancelled(any(), any(), any(), any())).thenReturn(1);

        ReportCreateResponse response = reportCreator.receive(REPORTER, listingRequest(ReportReason.FRAUD, null));

        verify(dailyLimiter).tryAcquire(REPORTER, lockAcquiredAt);
        verify(reportRepository).reviveCancelled(eq(EXISTING_REPORT_ID), any(), any(), eq(lockAcquiredAt));
        assertThat(response.receivedAt()).isEqualTo(lockAcquiredAt);
    }

    @Test
    @DisplayName("RPT-C38 잠금 재조회에서 다른 요청이 이미 되살린 것(RECEIVED)이 보이면 409(RPT001) - UPDATE·한도 차감 없음")
    void revivedByOtherRequestIsDuplicate() {
        givenExisting(ReportStatus.CANCELLED);
        givenLockedView(existing(ReportStatus.RECEIVED)); // 첫 조회는 CANCELLED, 잠금 재조회는 RECEIVED

        assertRejected(listingRequest(ReportReason.FRAUD, null), ReportErrorCode.DUPLICATE_ACTIVE_REPORT);
    }

    @Test
    @DisplayName("RPT-C38 조건부 UPDATE가 0행이면(이미 CANCELLED가 아님) 409(RPT001)")
    void revivalUpdateMissIsDuplicate() {
        givenExisting(ReportStatus.CANCELLED);
        when(reportRepository.reviveCancelled(any(), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> reportCreator.receive(REPORTER, listingRequest(ReportReason.FRAUD, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReportErrorCode.DUPLICATE_ACTIVE_REPORT);
        verify(reportRepository, never()).save(any());
    }
}
