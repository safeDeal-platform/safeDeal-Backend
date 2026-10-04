package com.safedeal.domain.listing.dto;

import com.safedeal.domain.listing.entity.Category;
import com.safedeal.domain.listing.entity.ItemCondition;
import com.safedeal.domain.listing.entity.Listing;
import com.safedeal.domain.listing.entity.ListingStatus;

import java.time.Instant;

/**
 * 매물 상세. API 명세서 구조를 따라 카테고리·지역을 중첩 객체로 묶는다. {@code images} ·
 * {@code seller} · {@code favoriteCount} · {@code verificationBadge}는 아직 없다 — 관련 도메인이
 * 붙기 전에 null로 내려보내면 프론트가 값이 없는 상태를 계약으로 오해한다.
 */
public record ListingDetailResponse(
        String publicId,
        String title,
        String description,
        int price,
        CategoryRef category,
        ItemCondition itemCondition,
        RegionRef region,
        ListingStatus status,
        int viewCount,
        Instant createdAt,
        /**
         * 수정 요청에 그대로 실어 보내는 값이다 — 그 사이 다른 사람이 먼저 고쳤으면 이 값이
         * 달라져 있어 수정이 실패한다. 여기서 안 주면 첫 수정에 쓸 값을 얻을 곳이 없다.
         */
        Long version
) {
    /** 카테고리는 숫자 id가 아니라 code로 노출한다. 표시명은 화면이 다시 조회하지 않도록 함께 준다. */
    public record CategoryRef(String code, String name, String parentName) {
        static CategoryRef from(Category category) {
            Category parent = category.getParent();
            return new CategoryRef(category.getCode(), category.getName(),
                    parent == null ? null : parent.getName());
        }
    }

    public record RegionRef(String sido, String sigungu) {
    }

    public static ListingDetailResponse from(Listing listing) {
        return new ListingDetailResponse(
                listing.getPublicId(),
                listing.getTitle(),
                listing.getDescription(),
                listing.getPrice(),
                CategoryRef.from(listing.getCategory()),
                listing.getItemCondition(),
                new RegionRef(listing.getRegionSido(), listing.getRegionSigungu()),
                listing.getStatus(),
                listing.getViewCount(),
                listing.getCreatedAt(),
                listing.getVersion());
    }
}
