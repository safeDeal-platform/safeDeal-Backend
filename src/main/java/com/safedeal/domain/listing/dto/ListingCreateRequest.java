package com.safedeal.domain.listing.dto;

import com.safedeal.domain.listing.entity.ItemCondition;
import com.safedeal.domain.listing.entity.Listing;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 매물 등록 요청.
 *
 * <p>카테고리는 숫자 id가 아니라 {@code code}로 받는다 — 시드 id는 환경마다 달라서 id로 받으면
 * 로컬에서 되던 요청이 운영에서 깨진다.
 *
 * <p><b>이미지(imageKeys)는 아직 받지 않는다.</b> presigned URL 발급이 S3 인프라를 기다리고 있어
 * 이 단계에서 key를 검증할 방법이 없다. 정책의 "이미지 1~5장 필수"는 이미지 업로드가 붙을 때
 * 함께 강제한다.
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
