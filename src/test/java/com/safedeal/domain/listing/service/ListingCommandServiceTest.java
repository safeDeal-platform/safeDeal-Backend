package com.safedeal.domain.listing.service;

import com.safedeal.domain.listing.dto.ListingCreateRequest;
import com.safedeal.domain.listing.dto.ListingStatusChangeRequest;
import com.safedeal.domain.listing.dto.ListingUpdateRequest;
import com.safedeal.domain.listing.dto.ListingCreateResponse;
import com.safedeal.domain.listing.dto.ListingUpdateResponse;
import com.safedeal.domain.listing.exception.ListingErrorCode;
import com.safedeal.global.exception.CommonErrorCode;
import com.safedeal.domain.listing.entity.Category;
import com.safedeal.domain.listing.entity.ItemCondition;
import com.safedeal.domain.listing.entity.Listing;
import com.safedeal.domain.listing.entity.ListingStatus;
import com.safedeal.domain.listing.repository.CategoryRepository;
import com.safedeal.domain.listing.repository.ListingRepository;
import com.safedeal.global.exception.BusinessException;
import com.safedeal.global.util.PublicIdGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ListingCommandServiceTest {

    @Mock ListingRepository listingRepository;
    @Mock CategoryRepository categoryRepository;
    @Mock PublicIdGenerator publicIdGenerator;

    @InjectMocks ListingCommandService listingCommandService;

    private static final String PUBLIC_ID = "01J3ABCDEFGHJKMNPQRSTVWXYZ";

    private Category leaf() {
        Category root = Category.root("DIGITAL", "디지털기기", 1);
        return Category.child("DIGITAL_PHONE", "스마트폰", root, 1);
    }

    private ListingCreateRequest request(String categoryCode) {
        return new ListingCreateRequest("아이폰 15 프로", 950_000, categoryCode, "상태 좋습니다",
                ItemCondition.LIKE_NEW, "서울특별시", "강남구");
    }

    private void setVerificationEnabled(boolean enabled) {
        ReflectionTestUtils.setField(listingCommandService, "verificationEnabled", enabled);
    }

    @Test
    @DisplayName("검증이 꺼져 있으면 등록 즉시 ACTIVE로 저장한다")
    void registersActiveWhenFlagOff() {
        setVerificationEnabled(false);
        when(categoryRepository.findByCode("DIGITAL_PHONE")).thenReturn(Optional.of(leaf()));
        when(publicIdGenerator.generate()).thenReturn(PUBLIC_ID);
        when(listingRepository.save(any(Listing.class))).thenAnswer(i -> i.getArgument(0));

        ListingCreateResponse response = listingCommandService.register(1L, request("DIGITAL_PHONE"));

        assertThat(response.publicId()).isEqualTo(PUBLIC_ID);
        assertThat(response.status()).isEqualTo(ListingStatus.ACTIVE);
    }

    @Test
    @DisplayName("검증이 켜져 있으면 검증 대기로 저장한다")
    void registersPendingWhenFlagOn() {
        setVerificationEnabled(true);
        when(categoryRepository.findByCode("DIGITAL_PHONE")).thenReturn(Optional.of(leaf()));
        when(publicIdGenerator.generate()).thenReturn(PUBLIC_ID);
        when(listingRepository.save(any(Listing.class))).thenAnswer(i -> i.getArgument(0));

        ListingCreateResponse response = listingCommandService.register(1L, request("DIGITAL_PHONE"));

        assertThat(response.status()).isEqualTo(ListingStatus.PENDING_VERIFICATION);
    }

    private Listing activeListing(Long sellerId) {
        return Listing.register(PUBLIC_ID, sellerId, "아이폰", "설명", 950_000,
                leaf(), ItemCondition.LIKE_NEW, "서울특별시", "강남구", false);
    }

    private ListingUpdateRequest updateRequest(int price, Long version) {
        return new ListingUpdateRequest("수정 제목", price, "DIGITAL_PHONE", "수정 설명",
                ItemCondition.USED, "서울특별시", "강남구", version);
    }

    @Test
    @DisplayName("남의 매물은 수정할 수 없다")
    void cannotUpdateOthersListing() {
        when(listingRepository.findByPublicIdAndDeletedAtIsNull(PUBLIC_ID))
                .thenReturn(Optional.of(activeListing(1L)));

        assertThatThrownBy(() ->
                listingCommandService.update(999L, PUBLIC_ID, updateRequest(900_000, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("판매자 본인");
    }

    @Test
    @DisplayName("남의 매물은 삭제할 수 없다")
    void cannotDeleteOthersListing() {
        when(listingRepository.findByPublicIdAndDeletedAtIsNull(PUBLIC_ID))
                .thenReturn(Optional.of(activeListing(1L)));

        assertThatThrownBy(() -> listingCommandService.delete(999L, PUBLIC_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("판매자 본인");
    }

    @Test
    @DisplayName("없는 매물이면 404를 던진다")
    void notFound() {
        when(listingRepository.findByPublicIdAndDeletedAtIsNull(PUBLIC_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> listingCommandService.delete(1L, PUBLIC_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("찾을 수 없습니다");
    }

    @Test
    @DisplayName("전이가 선점되어 0행이면 409를 던진다")
    void statusChangeConflict() {
        when(listingRepository.findByPublicIdAndDeletedAtIsNull(PUBLIC_ID))
                .thenReturn(Optional.of(activeListing(1L)));
        when(listingRepository.markSoldByOwner(any(), anyLong(), any(Instant.class))).thenReturn(0);

        assertThatThrownBy(() -> listingCommandService.changeStatus(1L, PUBLIC_ID,
                new ListingStatusChangeRequest(ListingStatusChangeRequest.Action.MARK_SOLD)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("처리할 수 없습니다");
    }

    // ── 수정 ────────────────────────────────────────────────────────────────

    private Listing ownedListing(ListingStatus status, Long version) {
        Listing listing = activeListing(1L);
        ReflectionTestUtils.setField(listing, "id", 55L);
        ReflectionTestUtils.setField(listing, "status", status);
        ReflectionTestUtils.setField(listing, "version", version);
        when(listingRepository.findByPublicIdAndDeletedAtIsNull(PUBLIC_ID))
                .thenReturn(Optional.of(listing));
        when(categoryRepository.findByCode("DIGITAL_PHONE")).thenReturn(Optional.of(leaf()));
        return listing;
    }

    private Object errorCodeOf(Runnable call) {
        return assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .actual() instanceof BusinessException e ? e.getErrorCode() : null;
    }

    @Test
    @DisplayName("수정하면 값이 바뀌고 flush 뒤의 version을 돌려준다")
    void updateAppliesChanges() {
        Listing listing = ownedListing(ListingStatus.ACTIVE, 3L);

        ListingUpdateResponse response =
                listingCommandService.update(1L, PUBLIC_ID, updateRequest(900_000, 3L));

        assertThat(listing.getTitle()).isEqualTo("수정 제목");
        assertThat(listing.getPrice()).isEqualTo(900_000);
        assertThat(response.publicId()).isEqualTo(PUBLIC_ID);
        verify(listingRepository).flush();
    }

    @Test
    @DisplayName("낡은 version으로 수정하면 409(C009)이고 값은 바뀌지 않는다")
    void updateRejectsStaleVersion() {
        Listing listing = ownedListing(ListingStatus.ACTIVE, 4L);

        assertThat(errorCodeOf(() ->
                listingCommandService.update(1L, PUBLIC_ID, updateRequest(900_000, 3L))))
                .isEqualTo(CommonErrorCode.CONCURRENT_MODIFICATION);
        assertThat(listing.getPrice()).isEqualTo(950_000);
    }

    @Test
    @DisplayName("팔렸거나 제재된 매물은 수정할 수 없다(400 C001)")
    void updateRejectsNonEditableStatus() {
        for (ListingStatus status : List.of(ListingStatus.SOLD, ListingStatus.BLOCKED)) {
            ownedListing(status, 3L);

            assertThat(errorCodeOf(() ->
                    listingCommandService.update(1L, PUBLIC_ID, updateRequest(900_000, 3L))))
                    .as(status.name())
                    .isEqualTo(CommonErrorCode.INVALID_INPUT);
        }
    }

    @Test
    @DisplayName("하루 3번째 가격 인하는 도메인 코드 LST001(400)로 옮겨진다")
    void updateMapsPriceDropLimit() {
        ownedListing(ListingStatus.ACTIVE, 3L);

        listingCommandService.update(1L, PUBLIC_ID, updateRequest(900_000, 3L));
        listingCommandService.update(1L, PUBLIC_ID, updateRequest(800_000, 3L));

        assertThat(errorCodeOf(() ->
                listingCommandService.update(1L, PUBLIC_ID, updateRequest(700_000, 3L))))
                .isEqualTo(ListingErrorCode.PRICE_DROP_LIMIT_EXCEEDED);
    }

    // ── 삭제 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("삭제는 읽어 둔 상태를 조건으로 건 UPDATE로 한다")
    void deleteUsesConditionalUpdate() {
        ownedListing(ListingStatus.SOLD, 3L);
        when(listingRepository.softDeleteByOwner(
                anyLong(), anyLong(), any(Instant.class), any(ListingStatus.class))).thenReturn(1);

        listingCommandService.delete(1L, PUBLIC_ID);

        verify(listingRepository).softDeleteByOwner(
                eq(55L), eq(1L), any(Instant.class), eq(ListingStatus.SOLD));
    }

    @Test
    @DisplayName("삭제하는 사이 상태가 바뀌어 0행이면 409(C004)이다")
    void deleteConflictsWhenStatusChanged() {
        ownedListing(ListingStatus.ACTIVE, 3L);
        when(listingRepository.softDeleteByOwner(
                anyLong(), anyLong(), any(Instant.class), any(ListingStatus.class))).thenReturn(0);

        assertThat(errorCodeOf(() -> listingCommandService.delete(1L, PUBLIC_ID)))
                .isEqualTo(CommonErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("제재된 매물 삭제는 403, 전이표에 없는 상태는 400이며 어느 쪽도 UPDATE를 던지지 않는다")
    void deleteRejectsBlockedAndNonDeletable() {
        ownedListing(ListingStatus.BLOCKED, 3L);
        assertThat(errorCodeOf(() -> listingCommandService.delete(1L, PUBLIC_ID)))
                .isEqualTo(CommonErrorCode.FORBIDDEN);

        ownedListing(ListingStatus.DRAFT, 3L);
        assertThat(errorCodeOf(() -> listingCommandService.delete(1L, PUBLIC_ID)))
                .isEqualTo(CommonErrorCode.INVALID_INPUT);

        verify(listingRepository, never()).softDeleteByOwner(
                anyLong(), anyLong(), any(Instant.class), any(ListingStatus.class));
    }

    // ── 상태 전이 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("MARK_SOLD는 판매완료 UPDATE만, RESTORE는 되돌리기 UPDATE만 부른다")
    void changeStatusRoutesByAction() {
        Listing listing = ownedListing(ListingStatus.ACTIVE, 3L);
        when(listingRepository.markSoldByOwner(any(), anyLong(), any(Instant.class))).thenReturn(1);
        when(listingRepository.restoreManualSoldByOwner(any(), anyLong(), any(Instant.class)))
                .thenReturn(1);
        when(listingRepository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.of(listing));

        listingCommandService.changeStatus(1L, PUBLIC_ID,
                new ListingStatusChangeRequest(ListingStatusChangeRequest.Action.MARK_SOLD));
        verify(listingRepository).markSoldByOwner(any(), anyLong(), any(Instant.class));
        verify(listingRepository, never())
                .restoreManualSoldByOwner(any(), anyLong(), any(Instant.class));

        listingCommandService.changeStatus(1L, PUBLIC_ID, new ListingStatusChangeRequest(
                ListingStatusChangeRequest.Action.RESTORE_MANUAL_SOLD));
        verify(listingRepository).restoreManualSoldByOwner(any(), anyLong(), any(Instant.class));
    }

    @Test
    @DisplayName("상태 전이 응답은 UPDATE 뒤에 다시 읽은 값이다")
    void changeStatusReturnsReloadedValues() {
        Listing sold = activeListing(1L);
        ReflectionTestUtils.setField(sold, "id", 55L);
        ReflectionTestUtils.setField(sold, "status", ListingStatus.SOLD);
        ReflectionTestUtils.setField(sold, "soldSource",
                com.safedeal.domain.listing.entity.SoldSource.MANUAL);
        when(listingRepository.findByPublicIdAndDeletedAtIsNull(PUBLIC_ID))
                .thenReturn(Optional.of(activeListing(1L)));
        when(listingRepository.markSoldByOwner(any(), any(), any(Instant.class))).thenReturn(1);
        when(listingRepository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.of(sold));

        var response = listingCommandService.changeStatus(1L, PUBLIC_ID,
                new ListingStatusChangeRequest(ListingStatusChangeRequest.Action.MARK_SOLD));

        assertThat(response.status()).isEqualTo(ListingStatus.SOLD);
        assertThat(response.soldSource())
                .isEqualTo(com.safedeal.domain.listing.entity.SoldSource.MANUAL);
    }

    // ── 조회수 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("관리자는 조회수에 세지 않고, 그 외에는 조회자 ID와 함께 올린다")
    void viewCountPolicy() {
        listingCommandService.increaseViewCount(PUBLIC_ID, 9L, true);
        verify(listingRepository, never()).increaseViewCount(any(), any());

        listingCommandService.increaseViewCount(PUBLIC_ID, 9L, false);
        verify(listingRepository).increaseViewCount(PUBLIC_ID, 9L);
    }

    @Test
    @DisplayName("없는 카테고리면 저장하지 않고 400을 던진다")
    void rejectsUnknownCategory() {
        when(categoryRepository.findByCode("NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> listingCommandService.register(1L, request("NOPE")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("존재하지 않는");
        verify(listingRepository, never()).save(any());
    }

    @Test
    @DisplayName("대분류로 등록하면 400을 던진다")
    void rejectsRootCategory() {
        when(categoryRepository.findByCode("DIGITAL"))
                .thenReturn(Optional.of(Category.root("DIGITAL", "디지털기기", 1)));

        assertThatThrownBy(() -> listingCommandService.register(1L, request("DIGITAL")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("중분류");
        verify(listingRepository, never()).save(any());
    }

    @Test
    @DisplayName("비활성 카테고리로는 새 매물을 등록할 수 없다")
    void rejectsInactiveCategory() {
        Category leaf = leaf();
        leaf.deactivate();
        when(categoryRepository.findByCode("DIGITAL_PHONE")).thenReturn(Optional.of(leaf));

        assertThatThrownBy(() -> listingCommandService.register(1L, request("DIGITAL_PHONE")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("사용하지 않는");
        verify(listingRepository, never()).save(any());
    }
}
