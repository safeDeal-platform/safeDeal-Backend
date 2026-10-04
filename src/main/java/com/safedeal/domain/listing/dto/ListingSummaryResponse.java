package com.safedeal.domain.listing.dto;

import com.safedeal.domain.listing.entity.Listing;
import com.safedeal.domain.listing.entity.ListingStatus;

import java.time.Instant;

/**
 * 목록 한 줄. {@code thumbnailUrl} · {@code verificationBadge}는 아직 없다 — 이미지 업로드·검증
 * 도메인이 붙기 전에 null로 내려보내면 프론트가 값이 없는 상태를 계약으로 오해한다.
 */
public record ListingSummaryResponse(
        String publicId,
        String title,
        int price,
        String categoryCode,
        String regionSido,
        String regionSigungu,
        ListingStatus status,
        Instant createdAt
) {
    public static ListingSummaryResponse from(Listing listing) {
        return new ListingSummaryResponse(
                listing.getPublicId(),
                listing.getTitle(),
                listing.getPrice(),
                listing.getCategory().getCode(),
                listing.getRegionSido(),
                listing.getRegionSigungu(),
                listing.getStatus(),
                listing.getCreatedAt());
    }
}
