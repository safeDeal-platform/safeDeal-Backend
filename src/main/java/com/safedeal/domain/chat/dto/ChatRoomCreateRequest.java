package com.safedeal.domain.chat.dto;

import com.safedeal.global.util.PublicIdGenerator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 채팅방 생성 요청(API 명세서 CHT-1). 필드명 {@code listingId}는 내부 PK가 아니라 매물의 ULID다. */
public record ChatRoomCreateRequest(

        @NotBlank(message = "매물 식별자는 필수입니다")
        @Size(min = PublicIdGenerator.LENGTH, max = PublicIdGenerator.LENGTH,
                message = "올바른 매물 식별자가 아닙니다")
        String listingId
) {
}
