package com.safedeal.domain.listing.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 알림 기준가 규칙을 컨테이너 없이 검증한다. 이 규칙이 깨지면 "찜한 값보다 싸졌다" 알림이
 * 안 가거나 같은 가격으로 반복해서 간다.
 */
class ListingFavoriteTest {

    private Listing listing(int price) {
        Category root = Category.root("DIGITAL", "디지털기기", 1);
        Category leaf = Category.child("DIGITAL_PHONE", "스마트폰", root, 1);
        return Listing.register("01J3ABCDEFGHJKMNPQRSTVWXYZ", 1L, "아이폰", "설명", price,
                leaf, ItemCondition.USED, "서울특별시", "강남구", false);
    }

    @Test
    @DisplayName("찜한 시점의 가격이 알림 기준가가 된다")
    void capturesPriceAtFavoriteTime() {
        ListingFavorite favorite = ListingFavorite.of(1L, listing(950_000));

        assertThat(favorite.getNotifyBasePrice()).isEqualTo(950_000);
        assertThat(favorite.getLastNotifiedAt()).isNull();
    }

    @Test
    @DisplayName("기준가보다 싸지면 기준가를 그 값으로 낮춘다")
    void lowersBaseWhenPriceDrops() {
        ListingFavorite favorite = ListingFavorite.of(1L, listing(950_000));
        Instant now = Instant.parse("2026-09-01T00:00:00Z");

        boolean notified = favorite.lowerBasePriceIfDropped(900_000);

        assertThat(notified).isTrue();
        assertThat(favorite.getNotifyBasePrice()).isEqualTo(900_000);
        // 판정만으로는 발송 기록이 찍히지 않는다 — 발송이 실패했는데 찍혀 있으면
        // 같은 가격으로는 다시 알릴 수 없게 된다.
        assertThat(favorite.getLastNotifiedAt()).isNull();

        favorite.markNotified(now);
        assertThat(favorite.getLastNotifiedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("한 번 내려간 기준가는 가격이 다시 올라도 따라 오르지 않는다")
    void doesNotRaiseBaseWhenPriceGoesBackUp() {
        ListingFavorite favorite = ListingFavorite.of(1L, listing(950_000));
        favorite.lowerBasePriceIfDropped(900_000);

        // 900,000까지 내렸다가 다시 950,000으로 올린 판매자. 기준가가 따라 오르면 다음에
        // 900,000으로 되돌리는 것만으로 "인하" 알림이 또 나간다.
        boolean notified = favorite.lowerBasePriceIfDropped(950_000);

        assertThat(notified).isFalse();
        assertThat(favorite.getNotifyBasePrice()).isEqualTo(900_000);
    }

    @Test
    @DisplayName("같은 가격으로는 다시 알리지 않는다")
    void doesNotNotifyOnSamePrice() {
        ListingFavorite favorite = ListingFavorite.of(1L, listing(950_000));

        boolean notified = favorite.lowerBasePriceIfDropped(950_000);

        assertThat(notified).isFalse();
    }
}
