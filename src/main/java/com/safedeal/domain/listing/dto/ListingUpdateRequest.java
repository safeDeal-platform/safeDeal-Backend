package com.safedeal.domain.listing.dto;

import com.safedeal.domain.listing.entity.ItemCondition;
import com.safedeal.domain.listing.entity.Listing;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 매물 수정 요청.
 *
 * <p>{@code version}은 필수다 — 클라이언트가 어떤 판을 보고 고쳤는지 알아야, 그 사이 다른
 * 수정이 반영됐을 때 덮어쓰지 않고 409로 되돌려줄 수 있다.
 *
 * <p>이미지(imageKeys)는 아직 받지 않는다. 이미지 변경 시 image_version을 올리고 검증 대기로
 * 되돌리는 규칙은 업로드가 붙을 때 함께 넣는다.
 */
public record ListingUpdateRequest(

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
        String regionSigungu,

        @NotNull(message = "version은 필수입니다")
        Long version
) {
}
