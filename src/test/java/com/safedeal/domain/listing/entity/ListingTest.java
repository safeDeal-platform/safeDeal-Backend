package com.safedeal.domain.listing.entity;

import java.time.LocalDate;

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
        assertThat(listing.isListable()).isTrue();
    }

    @Test
    @DisplayName("검증이 켜져 있으면 검증 대기로 시작해 공개되지 않는다")
    void flagOnWaitsForVerification() {
        Listing listing = register(950_000, leaf(), true);

        assertThat(listing.getStatus()).isEqualTo(ListingStatus.PENDING_VERIFICATION);
        assertThat(listing.isListable()).isFalse();
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
    @DisplayName("수정으로 지역을 바꿀 수 있다")
    void updateChangesRegion() {
        Listing listing = register(900_000, leaf(), false);
        Category category = listing.getCategory();

        // 등록 때 시/군/구를 잘못 넣으면 여기서 못 고칠 경우 삭제 후 재등록밖에 방법이 없다.
        listing.update("t", "d", 900_000, category, ItemCondition.USED,
                " 경기도 ", " 성남시 분당구 ", LocalDate.of(2026, 8, 31));

        assertThat(listing.getRegionSido()).isEqualTo("경기도");
        assertThat(listing.getRegionSigungu()).isEqualTo("성남시 분당구");
    }

    @Test
    @DisplayName("수정에 지역이 비면 거부한다")
    void updateRejectsBlankRegion() {
        Listing listing = register(900_000, leaf(), false);
        Category category = listing.getCategory();

        assertThatThrownBy(() -> listing.update("t", "d", 900_000, category,
                ItemCondition.USED, " ", "강남구", LocalDate.of(2026, 8, 31)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("regionSido");
    }

    @Test
    @DisplayName("가격 인하는 하루 2회까지만 된다")
    void priceDropLimit() {
        Listing listing = register(900_000, leaf(), false);
        LocalDate today = LocalDate.of(2026, 8, 31);
        Category category = listing.getCategory();

        listing.update("t", "d", 800_000, category, ItemCondition.USED, "서울특별시", "강남구", today);
        listing.update("t", "d", 700_000, category, ItemCondition.USED, "서울특별시", "강남구", today);

        assertThatThrownBy(() ->
                listing.update("t", "d", 600_000, category, ItemCondition.USED, "서울특별시", "강남구", today))
                .isInstanceOf(Listing.PriceDropLimitExceededException.class);
        assertThat(listing.getPrice()).isEqualTo(700_000);
    }

    @Test
    @DisplayName("날짜가 바뀌면 인하 횟수가 리셋된다")
    void priceDropResetsNextDay() {
        Listing listing = register(900_000, leaf(), false);
        Category category = listing.getCategory();
        LocalDate day1 = LocalDate.of(2026, 8, 31);

        listing.update("t", "d", 800_000, category, ItemCondition.USED, "서울특별시", "강남구", day1);
        listing.update("t", "d", 700_000, category, ItemCondition.USED, "서울특별시", "강남구", day1);

        listing.update("t", "d", 600_000, category, ItemCondition.USED, "서울특별시", "강남구", day1.plusDays(1));

        assertThat(listing.getPrice()).isEqualTo(600_000);
        assertThat(listing.getPriceDropCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("가격을 올리거나 그대로 두면 인하 횟수를 세지 않는다")
    void raisingPriceDoesNotCount() {
        Listing listing = register(900_000, leaf(), false);
        Category category = listing.getCategory();
        LocalDate today = LocalDate.of(2026, 8, 31);

        listing.update("t", "d", 950_000, category, ItemCondition.USED, "서울특별시", "강남구", today);
        listing.update("t", "d", 950_000, category, ItemCondition.USED, "서울특별시", "강남구", today);
        listing.update("t", "d", 990_000, category, ItemCondition.USED, "서울특별시", "강남구", today);

        assertThat(listing.getPriceDropCount()).isZero();
    }

    @Test
    @DisplayName("삭제하면 상태와 삭제 시각이 함께 바뀐다")
    void softDelete() {
        Listing listing = register(900_000, leaf(), false);

        listing.softDelete(java.time.Instant.parse("2026-08-31T00:00:00Z"));

        assertThat(listing.isDeleted()).isTrue();
        assertThat(listing.getStatus()).isEqualTo(ListingStatus.DELETED);
        assertThat(listing.isListable()).isFalse();
    }

    @Test
    @DisplayName("검증 대기 매물도 판매자가 삭제할 수 있다 - 철회 경로")
    void pendingCanBeDeleted() {
        Listing listing = register(900_000, leaf(), true);
        java.time.Instant now = java.time.Instant.now();

        // 재촬영 요구를 받고 그만두려는 판매자의 경로. 막으면 그 매물이 검증 대기로 영원히
        // 남는다. 수정은 이미 허용돼 있어(isEditable) 삭제만 막히는 비대칭이기도 했다.
        listing.softDelete(now);

        assertThat(listing.isDeleted()).isTrue();
        assertThat(listing.getDeletedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("이미 삭제된 매물을 다시 지워도 조용히 넘어간다")
    void deleteIsIdempotent() {
        Listing listing = register(900_000, leaf(), false);
        java.time.Instant first = java.time.Instant.parse("2026-08-31T00:00:00Z");
        listing.softDelete(first);

        listing.softDelete(java.time.Instant.parse("2026-09-01T00:00:00Z"));

        assertThat(listing.getDeletedAt()).isEqualTo(first);
    }

    @Test
    @DisplayName("판매자 본인 여부를 판정한다")
    void ownership() {
        Listing listing = register(950_000, leaf(), false);

        assertThat(listing.isOwnedBy(1L)).isTrue();
        assertThat(listing.isOwnedBy(2L)).isFalse();
    }
}
