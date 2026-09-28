package com.safedeal.domain.listing.dto;

import com.safedeal.domain.listing.entity.ItemCondition;
import com.safedeal.domain.listing.entity.Listing;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 매물 등록 요청. 카테고리는 숫자 id가 아니라 {@code code}로 받는다 — 시드 id는 환경마다 달라
 * id로 받으면 로컬에서 되던 요청이 운영에서 깨진다. 이미지(imageKeys)는 S3 presigned URL
 * 발급이 아직 없어 이번 단계에서는 받지 않는다.
 */
public record ListingCreateRequest(

        @NotBlank(message = "제목은 필수입니다")
        @Size(max = Listing.MAX_TITLE_LENGTH, message = "제목은 100자 이하여야 합니다")
        String title,

        @NotNull(message = "가격은 필수입니다")
        @Min(value = Listing.MIN_PRICE, message = "가격은 1,000원 이상이어야 합니다")
        @Max(value = Listing.MAX_PRICE, message = "가격은 100,000,000원 이하여야 합니다")
        Integer price,

        @NotBlank(message = "카테고리는 필수입니다")
        String categoryCode,

        @NotBlank(message = "설명은 필수입니다")
        @Size(max = Listing.MAX_DESCRIPTION_LENGTH, message = "설명은 2000자 이하여야 합니다")
        String description,

        @NotNull(message = "물품 상태는 필수입니다")
        ItemCondition itemCondition,

        @NotBlank(message = "시/도는 필수입니다")
        @Size(max = 20)
        String regionSido,

        @NotBlank(message = "시/군/구는 필수입니다")
        @Size(max = 20)
        String regionSigungu
) {
}
