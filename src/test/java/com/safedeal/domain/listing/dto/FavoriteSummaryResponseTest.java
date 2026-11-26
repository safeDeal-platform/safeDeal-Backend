package com.safedeal.domain.listing.dto;

import com.safedeal.domain.listing.entity.Category;
import com.safedeal.domain.listing.entity.ItemCondition;
import com.safedeal.domain.listing.entity.Listing;
import com.safedeal.domain.listing.entity.ListingFavorite;
import com.safedeal.domain.listing.entity.ListingStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 찜 목록 한 줄에서 제재·삭제 매물 가리기 (케이스 문서 .claude/notes/favorite-hide-sanctioned-cases.md, FH-01~18).
 * 가림 기준은 {@code !listing.isViewable()}(Q3·Q4) — ACTIVE·SOLD만 보이고, deletedAt이 있으면 상태와 무관하게 가린다.
 * 가리면 title·price·notifyBasePrice가 null(Q1·Q2), publicId·status·favoritedAt은 그대로다.
 */
class FavoriteSummaryResponseTest {

    private static final Long SELLER = 1L;
    private static final Long BUYER = 7L;
    private static final Instant FAVORITED_AT = Instant.parse("2026-08-30T00:00:00Z");

    // ── 보이는 매물 ───────────────────────────────────────

    @Test
    @DisplayName("FH-01 ACTIVE 매물은 title·price·status·publicId·notifyBasePrice·favoritedAt을 그대로 싣는다")
    void activeShowsEverything() {
        FavoriteSummaryResponse item = FavoriteSummaryResponse.from(favoriteOf(listing(ListingStatus.ACTIVE), 1_100_000));

        assertThat(item.listing()).isEqualTo(new FavoriteSummaryResponse.ListingRef(
                "01JFH0000000000000000000AA", "아이폰", 950_000, ListingStatus.ACTIVE));
        assertThat(item.notifyBasePrice()).isEqualTo(1_100_000);
        assertThat(item.favoritedAt()).isEqualTo(FAVORITED_AT);
    }

    @Test
    @DisplayName("FH-02 SOLD 매물은 title·price·notifyBasePrice를 그대로 싣는다 — 현재가와 찜 당시 가격이 뒤바뀌지 않는다")
    void soldShowsTitleAndPrice() {
        // 기준가를 현재가(950,000)와 다르게 둬야 두 값을 서로 바꿔 실어도 잡힌다.
        FavoriteSummaryResponse item = FavoriteSummaryResponse.from(favoriteOf(listing(ListingStatus.SOLD), 1_100_000));

        assertThat(item.listing().title()).isEqualTo("아이폰");
        assertThat(item.listing().price()).isEqualTo(950_000);
        assertThat(item.notifyBasePrice()).isEqualTo(1_100_000);
    }

    @Test
    @DisplayName("FH-10 최저가 1000원 ACTIVE 매물의 price는 숫자 1000이다 — 0 같은 특수값으로 숨김을 표시하지 않는다")
    void minimumPriceStaysNumber() {
        Listing listing = activeListing(Listing.MIN_PRICE);

        FavoriteSummaryResponse item = FavoriteSummaryResponse.from(favoriteOf(listing, Listing.MIN_PRICE));

        assertThat(item.listing().price()).isEqualTo(1_000);
        assertThat(item.notifyBasePrice()).isEqualTo(1_000);
    }

    // ── 가리는 매물 ───────────────────────────────────────

    @Test
    @DisplayName("FH-03 FH-11 BLOCKED 매물은 title·price·notifyBasePrice가 null, publicId·status·favoritedAt은 그대로")
    void blockedHidesTitlePriceAndBasePrice() {
        FavoriteSummaryResponse item = FavoriteSummaryResponse.from(favoriteOf(listing(ListingStatus.BLOCKED), 950_000));

        assertThat(item.listing().title()).isNull();
        assertThat(item.listing().price()).isNull();
        assertThat(item.listing().publicId()).isEqualTo("01JFH0000000000000000000AA");
        assertThat(item.listing().status()).isEqualTo(ListingStatus.BLOCKED);
        assertThat(item.notifyBasePrice()).isNull();
        assertThat(item.favoritedAt()).isEqualTo(FAVORITED_AT);
    }

    @Test
    @DisplayName("FH-04 FH-11 판매자 삭제(softDelete) 매물도 title·price·notifyBasePrice가 null, status는 DELETED")
    void softDeletedHidesTitlePriceAndBasePrice() {
        Listing listing = listing(ListingStatus.ACTIVE);
        listing.softDelete(Instant.parse("2026-09-01T00:00:00Z"));

        FavoriteSummaryResponse item = FavoriteSummaryResponse.from(favoriteOf(listing, 950_000));

        assertThat(item.listing().title()).isNull();
        assertThat(item.listing().price()).isNull();
        assertThat(item.listing().publicId()).isEqualTo("01JFH0000000000000000000AA");
        assertThat(item.listing().status()).isEqualTo(ListingStatus.DELETED);
        assertThat(item.notifyBasePrice()).isNull();
        assertThat(item.favoritedAt()).isEqualTo(FAVORITED_AT);
    }

    @ParameterizedTest(name = "FH-05 status={0}인데 deletedAt이 채워진 행은 가린다")
    @EnumSource(value = ListingStatus.class, names = {"ACTIVE", "SOLD"})
    @DisplayName("FH-05 deletedAt이 있으면 status가 ACTIVE·SOLD여도 가린다 (Q3)")
    void deletedAtWinsOverViewableStatus(ListingStatus status) {
        Listing listing = listing(status);
        ReflectionTestUtils.setField(listing, "deletedAt", Instant.parse("2026-09-01T00:00:00Z"));

        FavoriteSummaryResponse item = FavoriteSummaryResponse.from(favoriteOf(listing, 950_000));

        assertThat(item.listing().title()).isNull();
        assertThat(item.listing().price()).isNull();
        assertThat(item.notifyBasePrice()).isNull();
        assertThat(item.listing().status()).isEqualTo(status);
    }

    @Test
    @DisplayName("FH-05 status=DELETED인데 deletedAt이 비어 있는 행도 가린다 (Q3)")
    void deletedStatusWithoutDeletedAtIsHidden() {
        FavoriteSummaryResponse item = FavoriteSummaryResponse.from(favoriteOf(listing(ListingStatus.DELETED), 950_000));

        assertThat(item.listing().title()).isNull();
        assertThat(item.listing().price()).isNull();
        assertThat(item.notifyBasePrice()).isNull();
    }

    @ParameterizedTest(name = "FH-06 {0} 매물은 가린다")
    @EnumSource(value = ListingStatus.class, names = {"PENDING_VERIFICATION", "DRAFT"})
    @DisplayName("FH-06 PENDING_VERIFICATION·DRAFT도 허용 목록 밖이라 가린다 (Q4)")
    void notYetPublicIsHidden(ListingStatus status) {
        FavoriteSummaryResponse item = FavoriteSummaryResponse.from(favoriteOf(listing(status), 950_000));

        assertThat(item.listing().title()).isNull();
        assertThat(item.listing().price()).isNull();
        assertThat(item.notifyBasePrice()).isNull();
        assertThat(item.listing().status()).isEqualTo(status);
    }

    @Test
    @DisplayName("FH-08 BLOCKED에서 ACTIVE로 복귀하면 원래 title·price·notifyBasePrice가 다시 보인다")
    void restoredListingShowsAgain() {
        Listing listing = listing(ListingStatus.BLOCKED);
        ListingFavorite favorite = favoriteOf(listing, 1_100_000);
        assertThat(FavoriteSummaryResponse.from(favorite).listing().title()).isNull();

        ReflectionTestUtils.setField(listing, "status", ListingStatus.ACTIVE);
        FavoriteSummaryResponse restored = FavoriteSummaryResponse.from(favorite);

        assertThat(restored.listing().title()).isEqualTo("아이폰");
        assertThat(restored.listing().price()).isEqualTo(950_000);
        assertThat(restored.notifyBasePrice()).isEqualTo(1_100_000);
    }

    @Test
    @DisplayName("FH-17 판매자 본인이 찜한 자기 매물이 BLOCKED여도 똑같이 가린다 (Q6)")
    void sellersOwnFavoriteIsHiddenToo() {
        Listing listing = listing(ListingStatus.BLOCKED);
        ListingFavorite own = ListingFavorite.of(SELLER, listing, 950_000);
        ReflectionTestUtils.setField(own, "createdAt", FAVORITED_AT);

        FavoriteSummaryResponse item = FavoriteSummaryResponse.from(own);

        assertThat(item.listing().title()).isNull();
        assertThat(item.listing().price()).isNull();
        assertThat(item.notifyBasePrice()).isNull();
    }

    // ── 동시성 (Q8) ───────────────────────────────────────
    // 스레드 경합(조회 도중 제재 커밋)은 일부러 테스트하지 않는다(Q8 승인). 응답은 findPage가 읽은
    // Listing 한 개에서만 만들어지므로, 상태별로 "실린 status ↔ 가림 여부"가 맞는지만 고정한다.

    @ParameterizedTest(name = "FH-18 status={0}")
    @EnumSource(ListingStatus.class)
    @DisplayName("FH-18 모든 상태에서 실린 status와 가림 여부가 일치한다 (판단은 같은 Listing 한 번)")
    void statusMatchesHidingForEveryStatus(ListingStatus status) {
        Listing listing = listing(status);

        FavoriteSummaryResponse item = FavoriteSummaryResponse.from(favoriteOf(listing, 950_000));

        // 판단 기준(isViewable)과 실린 status가 같은 객체에서 와야 "ACTIVE인데 제목 없음",
        // "BLOCKED인데 제목 보임"이 생기지 않는다.
        boolean visible = status == ListingStatus.ACTIVE || status == ListingStatus.SOLD;
        assertThat(item.listing().status()).isEqualTo(status);
        assertThat(item.listing().title()).isEqualTo(visible ? "아이폰" : null);
        assertThat(item.listing().price()).isEqualTo(visible ? 950_000 : null);
        assertThat(item.notifyBasePrice()).isEqualTo(visible ? 950_000 : null);
    }

    // ── 준비 데이터 ───────────────────────────────────────

    /** 원하는 상태의 매물. DRAFT·BLOCKED·DELETED는 운영 경로 대신 필드로 바로 맞춘다(단위 테스트라 DB가 없다). */
    private Listing listing(ListingStatus status) {
        Listing listing = activeListing(950_000);
        ReflectionTestUtils.setField(listing, "status", status);
        return listing;
    }

    /** 운영 등록 경로 그대로(검증 구간 꺼짐 → ACTIVE). 가격 검증(1000~1억)도 여기서 탄다. */
    private Listing activeListing(int price) {
        Category root = Category.root("DIGITAL", "디지털기기", 1);
        Category leaf = Category.child("DIGITAL_PHONE", "스마트폰", root, 1);
        return Listing.register("01JFH0000000000000000000AA", SELLER, "아이폰", "설명", price,
                leaf, ItemCondition.USED, "서울특별시", "강남구", false);
    }

    private ListingFavorite favoriteOf(Listing listing, int notifyBasePrice) {
        ListingFavorite favorite = ListingFavorite.of(BUYER, listing, notifyBasePrice);
        ReflectionTestUtils.setField(favorite, "createdAt", FAVORITED_AT);
        return favorite;
    }
}
