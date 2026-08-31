package com.safedeal.domain.listing.service;

import com.safedeal.domain.listing.dto.ListingCreateRequest;
import com.safedeal.domain.listing.dto.ListingStatusChangeRequest;
import com.safedeal.domain.listing.dto.ListingUpdateRequest;
import com.safedeal.domain.listing.dto.ListingCreateResponse;
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

import java.util.Optional;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.anyLong;
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
                ItemCondition.USED, version);
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
