package com.safedeal.domain.listing.dto;

/**
 * 찜 등록·해제 결과.
 *
 * <p>토글이라 "지금 찜된 상태인가" 하나만 돌려준다 — 클라이언트가 하트 아이콘을 그 값으로
 * 그대로 칠하면 되고, 요청이 실제로 상태를 바꿨는지 아닌지는 알 필요가 없다.
 */
public record FavoriteToggleResponse(boolean favorited) {

    public static FavoriteToggleResponse on() {
        return new FavoriteToggleResponse(true);
    }

    public static FavoriteToggleResponse off() {
        return new FavoriteToggleResponse(false);
    }
}
