package com.safedeal.domain.chat.dto;

import jakarta.validation.constraints.NotNull;

/** 읽음 처리 요청(API 명세서 CHT-3). */
public record ChatReadRequest(

        @NotNull(message = "lastReadMessageId는 필수입니다")
        Long lastReadMessageId
) {
}
