package com.safedeal.domain.listing.service;

import com.safedeal.domain.listing.dto.FavoriteToggleResponse;
import com.safedeal.domain.listing.entity.Category;
import com.safedeal.domain.listing.entity.ItemCondition;
import com.safedeal.domain.listing.entity.Listing;
import com.safedeal.domain.listing.entity.ListingFavorite;
import com.safedeal.domain.listing.repository.ListingFavoriteRepository;
import com.safedeal.domain.listing.repository.ListingRepository;
import com.safedeal.global.exception.BusinessException;
import com.safedeal.domain.listing.exception.ListingErrorCode;
import com.safedeal.global.exception.CommonErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FavoriteCommandServiceTest {

    @Mock ListingRepository listingRepository;
    @Mock ListingFavoriteRepository listingFavoriteRepository;

    @InjectMocks FavoriteCommandService favoriteCommandService;

    private static final String PUBLIC_ID = "01J3ABCDEFGHJKMNPQRSTVWXYZ";

    private Listing listing() {
        return listing(false);
    }

    private Listing listing(boolean verificationEnabled) {
        Category root = Category.root("DIGITAL", "디지털기기", 1);
        Category leaf = Category.child("DIGITAL_PHONE", "스마트폰", root, 1);
        return Listing.register(PUBLIC_ID, 1L, "아이폰", "설명", 950_000,
                leaf, ItemCondition.USED, "서울특별시", "강남구", verificationEnabled);
    }

    @Test
    @DisplayName("찜하면 그 시점 가격을 기준가로 넘긴다")
    void savesFavoriteWithCurrentPriceAsBase() {
        Listing listing = listing();
        ReflectionTestUtils.setField(listing, "id", 55L);
        when(listingRepository.findByPublicIdAndDeletedAtIsNull(PUBLIC_ID))
                .thenReturn(Optional.of(listing));

        FavoriteToggleResponse response = favoriteCommandService.add(2L, PUBLIC_ID);

        assertThat(response.favorited()).isTrue();
        verify(listingFavoriteRepository)
                .insertIfAbsent(eq(2L), eq(55L), eq(950_000), any(Instant.class));
    }

    @Test
    @DisplayName("중복 판정을 서비스에서 미리 하지 않는다 — 조회와 삽입 사이에 경합 창이 생긴다")
    void doesNotPreCheckExistence() {
        Listing listing = listing();
        ReflectionTestUtils.setField(listing, "id", 55L);
        when(listingRepository.findByPublicIdAndDeletedAtIsNull(PUBLIC_ID))
                .thenReturn(Optional.of(listing));

        favoriteCommandService.add(2L, PUBLIC_ID);

        // 판정은 DB의 UNIQUE 제약이 한다. 실제 동시 요청 동작은 FavoriteConcurrencyTest에서 본다.
        verify(listingFavoriteRepository, never()).findByUserIdAndListing(any(), any());
        verify(listingFavoriteRepository, never()).save(any(ListingFavorite.class));
    }

    @Test
    @DisplayName("공개 상태가 아닌 매물은 404가 아니라 409로 거부한다")
    void rejectsNonActiveListingWithConflict() {
        // 검증 대기 매물. 팔렸거나 제재된 매물도 같은 경로로 걸린다 — 판정은 isListable() 하나다.
        Listing listing = listing(true);
        when(listingRepository.findByPublicIdAndDeletedAtIsNull(PUBLIC_ID))
                .thenReturn(Optional.of(listing));

        // 상세 조회는 팔린 매물도 200으로 응답한다. 여기서 404를 주면 방금 화면에 띄운
        // 매물이 존재하지 않는다는 뜻이 되어 클라이언트가 안내 문구를 만들 수 없다.
        assertThatThrownBy(() -> favoriteCommandService.add(2L, PUBLIC_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ListingErrorCode.FAVORITE_TARGET_NOT_LISTABLE);
        verify(listingFavoriteRepository, never())
                .insertIfAbsent(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("없는 매물을 찜하면 404를 던진다 — 상태 때문에 막힌 경우와 구분된다")
    void rejectsUnknownListingWithNotFound() {
        when(listingRepository.findByPublicIdAndDeletedAtIsNull(PUBLIC_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> favoriteCommandService.add(2L, PUBLIC_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    @DisplayName("해제하면 단일 DELETE로 지운다")
    void deletesRowOnRemove() {
        Listing listing = listing();
        ReflectionTestUtils.setField(listing, "id", 55L);
        when(listingRepository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.of(listing));
        when(listingFavoriteRepository.deleteByUserIdAndListingId(2L, 55L)).thenReturn(1);

        FavoriteToggleResponse response = favoriteCommandService.remove(2L, PUBLIC_ID);

        assertThat(response.favorited()).isFalse();
        verify(listingFavoriteRepository).deleteByUserIdAndListingId(2L, 55L);
        // 조회 후 delete(entity)로 지우면 SELECT와 DELETE 사이 경합으로 409가 난다.
        verify(listingFavoriteRepository, never()).findByUserIdAndListing(any(), any());
    }

    @Test
    @DisplayName("찜하지 않은 매물을 해제해도 성공으로 돌려준다")
    void removeIsIdempotent() {
        Listing listing = listing();
        ReflectionTestUtils.setField(listing, "id", 55L);
        when(listingRepository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.of(listing));
        when(listingFavoriteRepository.deleteByUserIdAndListingId(2L, 55L)).thenReturn(0);

        assertThat(favoriteCommandService.remove(2L, PUBLIC_ID).favorited()).isFalse();
    }

    @Test
    @DisplayName("삭제된 매물도 해제는 된다 — 목록에 남아 있으니 지울 수 있어야 한다")
    void allowsRemovingFavoriteOfDeletedListing() {
        Listing listing = listing();
        ReflectionTestUtils.setField(listing, "id", 55L);
        listing.softDelete(Instant.now());
        when(listingRepository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.of(listing));
        when(listingFavoriteRepository.deleteByUserIdAndListingId(2L, 55L)).thenReturn(1);

        assertThat(favoriteCommandService.remove(2L, PUBLIC_ID).favorited()).isFalse();
        verify(listingFavoriteRepository).deleteByUserIdAndListingId(2L, 55L);
    }
}
