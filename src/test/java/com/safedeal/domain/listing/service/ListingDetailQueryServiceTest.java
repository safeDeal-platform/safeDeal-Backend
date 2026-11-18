package com.safedeal.domain.listing.service;

import com.safedeal.domain.listing.dto.ListingDetailResponse;
import com.safedeal.domain.listing.entity.Category;
import com.safedeal.domain.listing.entity.ItemCondition;
import com.safedeal.domain.listing.entity.Listing;
import com.safedeal.domain.listing.entity.ListingStatus;
import com.safedeal.domain.listing.repository.CategoryRepository;
import com.safedeal.domain.listing.repository.ListingFavoriteRepository;
import com.safedeal.domain.listing.repository.ListingRepository;
import com.safedeal.domain.user.entity.User;
import com.safedeal.domain.user.repository.UserRepository;
import com.safedeal.global.exception.BusinessException;
import com.safedeal.global.exception.CommonErrorCode;
import com.safedeal.global.response.CursorCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 매물 상세 확장(LD)의 서비스 분기를 컨테이너 없이 고정한다 — 실제 DB 흐름은 ListingDetailApiTest.
 * 여기서는 "어느 id로 무엇을 물었는가"처럼 응답만 봐서는 드러나지 않는 것을 본다.
 *
 * <p>판매자는 {@code findById} 한 번으로 읽고 {@code deletedAt}으로 탈퇴를 가른다(D1) —
 * 탈퇴(행 있음)와 행 없음(WARN 대상)을 구분하려면 탈퇴 제외 조회로는 둘이 똑같이 비어 버린다.
 */
@ExtendWith(MockitoExtension.class)
class ListingDetailQueryServiceTest {

    private static final String PUBLIC_ID = "01J00000000000000000000001";
    private static final long LISTING_ID = 7L;
    private static final long SELLER_ID = 1L;
    private static final long VIEWER_ID = 42L;

    @Mock ListingRepository listingRepository;
    @Mock CategoryRepository categoryRepository;
    @Mock CursorCodec cursorCodec;
    @Mock UserRepository userRepository;
    @Mock ListingFavoriteRepository listingFavoriteRepository;
    ListingQueryService service;

    @BeforeEach
    void setUp() {
        // 합의한 생성자 모양 그대로 만든다. categoryRepository·cursorCodec은 목록 조회용이라 상세 경로에서 쓰지 않는다.
        service = new ListingQueryService(listingRepository, categoryRepository, cursorCodec,
                userRepository, listingFavoriteRepository);
    }

    private Listing listing(ListingStatus status) {
        Category leaf = Category.child("DIGITAL_PHONE", "스마트폰", Category.root("DIGITAL", "디지털기기", 1), 1);
        Listing listing = Listing.register(PUBLIC_ID, SELLER_ID, "아이폰", "설명", 950_000,
                leaf, ItemCondition.USED, "서울특별시", "강남구", false);
        ReflectionTestUtils.setField(listing, "id", LISTING_ID);
        ReflectionTestUtils.setField(listing, "status", status);
        return listing;
    }

    private User seller(int trustScore) {
        User seller = User.createLocal("seller@safedeal.test", "hash", "판매자");
        ReflectionTestUtils.setField(seller, "id", SELLER_ID);
        seller.applyTrustScore(trustScore, false);
        return seller;
    }

    private void givenListing(ListingStatus status) {
        when(listingRepository.findByPublicIdAndDeletedAtIsNull(PUBLIC_ID)).thenReturn(Optional.of(listing(status)));
    }

    @Test
    @DisplayName("LD-01 LD-05 LD-06 비로그인: 판매자 id로 판매자를, 매물 id로 찜 수를 묻고 내 찜 여부는 묻지 않는다")
    void anonymousDetail() {
        givenListing(ListingStatus.ACTIVE);
        User seller = seller(300);
        when(userRepository.findById(SELLER_ID)).thenReturn(Optional.of(seller));
        when(listingFavoriteRepository.countByListingId(LISTING_ID)).thenReturn(3L);

        ListingDetailResponse detail = service.getListing(PUBLIC_ID, null);

        assertThat(detail.seller()).as("seller").isNotNull();
        assertThat(detail.seller().publicId()).isEqualTo(seller.getPublicId());
        assertThat(detail.seller().nickname()).isEqualTo("판매자");
        // BigDecimal.equals는 scale까지 본다 — 30과 30.0을 가른다.
        assertThat(detail.seller().trustScore()).isEqualTo(new BigDecimal("30.0"));
        assertThat(detail.favoriteCount()).isEqualTo(3L);
        assertThat(detail.favorited()).isNull();
        verify(listingFavoriteRepository, never()).existsByUserIdAndListingId(any(), any());
    }

    @Test
    @DisplayName("LD-02 로그인: 내 찜 여부는 (보는 사람 id, 매물 id) 순서로 묻는다 — 판매자 id를 넣거나 인자를 뒤바꾸면 실패")
    void loggedInDetailAsksWithViewerAndListing() {
        givenListing(ListingStatus.ACTIVE);
        when(userRepository.findById(SELLER_ID)).thenReturn(Optional.of(seller(500)));
        when(listingFavoriteRepository.countByListingId(LISTING_ID)).thenReturn(1L);
        when(listingFavoriteRepository.existsByUserIdAndListingId(VIEWER_ID, LISTING_ID)).thenReturn(true);

        ListingDetailResponse detail = service.getListing(PUBLIC_ID, VIEWER_ID);

        assertThat(detail.favorited()).isTrue();
        assertThat(detail.favoriteCount()).isEqualTo(1L);
        verify(listingFavoriteRepository).existsByUserIdAndListingId(VIEWER_ID, LISTING_ID);
    }

    @Test
    @DisplayName("LD-22 판매자가 탈퇴했으면(findById가 deletedAt 있는 행을 돌려줌) seller는 null, 나머지는 채운다 (D1)")
    void withdrawnSellerIsNull() {
        givenListing(ListingStatus.SOLD);
        User withdrawn = seller(500);
        ReflectionTestUtils.setField(withdrawn, "deletedAt", Instant.parse("2026-11-01T00:00:00Z"));
        when(userRepository.findById(SELLER_ID)).thenReturn(Optional.of(withdrawn));
        when(listingFavoriteRepository.countByListingId(LISTING_ID)).thenReturn(2L);
        when(listingFavoriteRepository.existsByUserIdAndListingId(VIEWER_ID, LISTING_ID)).thenReturn(false);

        ListingDetailResponse detail = service.getListing(PUBLIC_ID, VIEWER_ID);

        assertThat(detail.seller()).isNull();
        assertThat(detail.favoriteCount()).isEqualTo(2L);
        assertThat(detail.favorited()).isFalse();
    }

    @Test
    @DisplayName("LD-24 판매자 행이 없으면(findById가 비면) seller는 null, 나머지는 채운다")
    void missingSellerRowIsNull() {
        givenListing(ListingStatus.SOLD);
        when(userRepository.findById(SELLER_ID)).thenReturn(Optional.empty());
        when(listingFavoriteRepository.countByListingId(LISTING_ID)).thenReturn(2L);
        when(listingFavoriteRepository.existsByUserIdAndListingId(VIEWER_ID, LISTING_ID)).thenReturn(false);

        ListingDetailResponse detail = service.getListing(PUBLIC_ID, VIEWER_ID);

        assertThat(detail.seller()).isNull();
        assertThat(detail.favoriteCount()).isEqualTo(2L);
        assertThat(detail.favorited()).isFalse();
    }

    @Test
    @DisplayName("LD-13 LD-34 없는 매물은 404이고 판매자·찜 저장소를 건드리지 않는다")
    void unknownListingTouchesNothingElse() {
        when(listingRepository.findByPublicIdAndDeletedAtIsNull(PUBLIC_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getListing(PUBLIC_ID, VIEWER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
        verifyNoInteractions(userRepository, listingFavoriteRepository);
    }

    @Test
    @DisplayName("LD-14 LD-34 비공개(BLOCKED) 매물도 공개 판정이 먼저다 — 404이고 추가 조회가 없다")
    void hiddenListingTouchesNothingElse() {
        givenListing(ListingStatus.BLOCKED);

        assertThatThrownBy(() -> service.getListing(PUBLIC_ID, VIEWER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
        verifyNoInteractions(userRepository, listingFavoriteRepository);
    }

    @Test
    @DisplayName("LD-25 찜 수 조회가 DB 장애로 실패하면 삼키지 않고 그대로 올린다 (전역 처리기가 500 C007)")
    void favoriteCountFailurePropagates() {
        givenListing(ListingStatus.ACTIVE);
        // 조회 순서는 구현 재량이라 판매자 조회는 있어도 없어도 된다.
        lenient().when(userRepository.findById(anyLong())).thenReturn(Optional.of(seller(500)));
        when(listingFavoriteRepository.countByListingId(LISTING_ID))
                .thenThrow(new DataAccessResourceFailureException("Communications link failure"));

        assertThatThrownBy(() -> service.getListing(PUBLIC_ID, null))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }
}
