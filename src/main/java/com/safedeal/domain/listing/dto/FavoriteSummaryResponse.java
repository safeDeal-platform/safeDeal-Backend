package com.safedeal.domain.listing.dto;

import com.safedeal.domain.listing.entity.Listing;
import com.safedeal.domain.listing.entity.ListingFavorite;
import com.safedeal.domain.listing.entity.ListingStatus;

import java.time.Instant;

/**
 * 내 찜 목록 한 줄. 매물 상태를 그대로 실어 보낸다 — 팔렸거나 내려간 매물도 목록에 남아서,
 * 클라이언트가 "지금 거래할 수 없는 상품"으로 표시하려면 상태를 알아야 한다.
 * {@code notifyBasePrice}는 "찜할 때보다 n% 저렴"을 프론트가 서버에 다시 묻지 않고 계산하게 해준다.
 * 제재·삭제 등으로 볼 수 없는 매물({@code !isViewable()})은 title·price·notifyBasePrice를 null로 가린다.
 */
public record FavoriteSummaryResponse(
        ListingRef listing,
        Integer notifyBasePrice,
        Instant favoritedAt
) {
    public record ListingRef(String publicId, String title, Integer price, ListingStatus status) {
        static ListingRef from(Listing listing, boolean visible) {
            return new ListingRef(listing.getPublicId(),
                    visible ? listing.getTitle() : null,
                    visible ? Integer.valueOf(listing.getPrice()) : null,
                    listing.getStatus());
        }
    }

    public static FavoriteSummaryResponse from(ListingFavorite favorite) {
        Listing listing = favorite.getListing();
        boolean visible = listing.isViewable();
        return new FavoriteSummaryResponse(
                ListingRef.from(listing, visible),
                visible ? Integer.valueOf(favorite.getNotifyBasePrice()) : null,
                favorite.getCreatedAt());
    }
}
