package com.safedeal.domain.listing.dto;

import com.safedeal.domain.listing.entity.Category;
import com.safedeal.domain.listing.entity.ItemCondition;
import com.safedeal.domain.listing.entity.Listing;
import com.safedeal.domain.listing.entity.ListingStatus;

import java.time.Instant;

/**
 * 매물 상세. 구조는 API 명세서를 따른다 — 카테고리와 지역을 중첩 객체로 묶는다.
 *
 * <p>명세의 {@code images} · {@code seller} · {@code favoriteCount} · {@code verificationBadge}는
 * 아직 넣지 않는다. 이미지 업로드·유저·찜·검증 도메인이 붙은 뒤에 채운다. 지금 빈 값으로
 * 내려보내면 프론트가 "값이 없는 상태"를 계약으로 오해한다.
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
         * 수정 요청이 실어 보내야 하는 낙관적 락 버전. 여기서 내려주지 않으면 클라이언트가
         * 첫 수정에 쓸 값을 얻을 곳이 없다 — 수정 응답에만 실으면 "수정에 성공해야 수정할
         * 수 있는" 상태가 된다.
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
