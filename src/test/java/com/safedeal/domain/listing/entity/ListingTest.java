package com.safedeal.domain.listing.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ListingTest {

    private static final String PUBLIC_ID = "01J3ABCDEFGHJKMNPQRSTVWXYZ";

    private Category leaf() {
        Category root = Category.root("DIGITAL", "디지털기기", 1);
        return Category.child("DIGITAL_PHONE", "스마트폰", root, 1);
    }

    private Listing register(int price, Category category, boolean verificationEnabled) {
        return Listing.register(PUBLIC_ID, 1L, "아이폰 15 프로", "상태 좋습니다",
                price, category, ItemCondition.LIKE_NEW, "서울특별시", "강남구", verificationEnabled);
    }

    @Test
    @DisplayName("검증이 꺼져 있으면 등록 즉시 공개된다")
    void flagOffGoesActive() {
        Listing listing = register(950_000, leaf(), false);

        assertThat(listing.getStatus()).isEqualTo(ListingStatus.ACTIVE);
        assertThat(listing.isVisibleToPublic()).isTrue();
    }

    @Test
    @DisplayName("검증이 켜져 있으면 검증 대기로 시작해 공개되지 않는다")
    void flagOnWaitsForVerification() {
        Listing listing = register(950_000, leaf(), true);

        assertThat(listing.getStatus()).isEqualTo(ListingStatus.PENDING_VERIFICATION);
        assertThat(listing.isVisibleToPublic()).isFalse();
    }

    @Test
    @DisplayName("대분류에는 등록할 수 없다")
    void rootCategoryRejected() {
        Category root = Category.root("DIGITAL", "디지털기기", 1);

        assertThatThrownBy(() -> register(950_000, root, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("중분류");
    }

    @Test
    @DisplayName("가격 하한 미만과 상한 초과를 막는다")
    void priceBounds() {
        assertThatThrownBy(() -> register(999, leaf(), false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> register(100_000_001, leaf(), false))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(register(1_000, leaf(), false).getPrice()).isEqualTo(1_000);
        assertThat(register(100_000_000, leaf(), false).getPrice()).isEqualTo(100_000_000);
    }

    @Test
    @DisplayName("등록 직후 값 - 조회수 0, 인하 0회, 이미지 버전 1, 미삭제")
    void initialState() {
        Listing listing = register(950_000, leaf(), false);

        assertThat(listing.getViewCount()).isZero();
        assertThat(listing.getPriceDropCount()).isZero();
        assertThat(listing.getImageVersion()).isEqualTo(1L);
        assertThat(listing.isDeleted()).isFalse();
        assertThat(listing.getSoldSource()).isNull();
    }

    @Test
    @DisplayName("제목과 지역의 앞뒤 공백은 잘라낸다")
    void trimsText() {
        Listing listing = Listing.register(PUBLIC_ID, 1L, "  아이폰  ", "설명",
                950_000, leaf(), ItemCondition.USED, " 서울특별시 ", " 강남구 ", false);

        assertThat(listing.getTitle()).isEqualTo("아이폰");
        assertThat(listing.getRegionSido()).isEqualTo("서울특별시");
        assertThat(listing.getRegionSigungu()).isEqualTo("강남구");
    }

    @Test
    @DisplayName("판매자 본인 여부를 판정한다")
    void ownership() {
        Listing listing = register(950_000, leaf(), false);

        assertThat(listing.isOwnedBy(1L)).isTrue();
        assertThat(listing.isOwnedBy(2L)).isFalse();
    }
}
