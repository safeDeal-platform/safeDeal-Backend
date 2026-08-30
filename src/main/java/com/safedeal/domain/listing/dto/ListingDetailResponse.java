package com.safedeal.domain.listing.dto;

import com.safedeal.domain.listing.entity.ItemCondition;
import com.safedeal.domain.listing.entity.Listing;
import com.safedeal.domain.listing.entity.ListingStatus;

import java.time.Instant;

/** 상세. 판매자는 내부 id가 아니라 public_id로 노출해야 하나 users가 아직 없어 이번 범위 밖이다. */
public record ListingDetailResponse(
        String publicId,
        String title,
        String description,
        int price,
        String categoryCode,
        String categoryName,
        ItemCondition itemCondition,
        String regionSido,
        String regionSigungu,
        ListingStatus status,
        int viewCount,
        Instant createdAt,
        Instant updatedAt
) {
    public static ListingDetailResponse from(Listing listing) {
        return new ListingDetailResponse(
                listing.getPublicId(),
                listing.getTitle(),
                listing.getDescription(),
                listing.getPrice(),
                listing.getCategory().getCode(),
                listing.getCategory().getName(),
                listing.getItemCondition(),
                listing.getRegionSido(),
                listing.getRegionSigungu(),
                listing.getStatus(),
                listing.getViewCount(),
                listing.getCreatedAt(),
                listing.getUpdatedAt());
    }
}
