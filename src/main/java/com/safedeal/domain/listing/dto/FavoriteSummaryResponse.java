package com.safedeal.domain.listing.dto;

import com.safedeal.domain.listing.entity.Listing;
import com.safedeal.domain.listing.entity.ListingFavorite;
import com.safedeal.domain.listing.entity.ListingStatus;

import java.time.Instant;

/**
 * 내 찜 목록 한 줄.
 *
 * <p>매물 상태를 그대로 실어 보낸다 — 팔렸거나 내려간 매물도 목록에 남으므로, 클라이언트가
 * "현재 거래할 수 없는 상품"으로 표시하려면 상태를 알아야 한다.
 *
 * <p>{@code notifyBasePrice}를 함께 주면 프론트가 "찜할 때보다 n% 저렴"을 서버 왕복 없이 계산한다.
 *
 * <p>{@code thumbnailUrl}은 이미지 업로드가 붙은 뒤에 채운다. 지금 null로 내려보내면 프론트가
 * "값이 없는 상태"를 계약으로 오해한다.
 */
public record FavoriteSummaryResponse(
        ListingRef listing,
        int notifyBasePrice,
        Instant favoritedAt
) {
    public record ListingRef(String publicId, String title, int price, ListingStatus status) {
        static ListingRef from(Listing listing) {
            return new ListingRef(listing.getPublicId(), listing.getTitle(),
                    listing.getPrice(), listing.getStatus());
        }
    }

    public static FavoriteSummaryResponse from(ListingFavorite favorite) {
        return new FavoriteSummaryResponse(
                ListingRef.from(favorite.getListing()),
                favorite.getNotifyBasePrice(),
                favorite.getCreatedAt());
    }
}
