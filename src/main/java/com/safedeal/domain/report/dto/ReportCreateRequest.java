package com.safedeal.domain.report.dto;

import com.safedeal.domain.report.entity.ReportReason;
import com.safedeal.domain.report.entity.ReportTargetType;
import com.safedeal.global.util.PublicIdGenerator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 신고 접수 요청(POST /api/reports). {@code targetId}는 내부 PK가 아니라 대상(매물·채팅방)의 ULID다.
 *
 * <p>{@code detail}은 여기서 다듬지 않는다 — 1,000자 검사는 원문 기준이고(D4), 공백뿐인 값의 null 변환은
 * 엔티티 몫이다(Q6). {@code evidenceMessageId}는 채팅 신고에만 쓰며 매물 신고에 있으면 400이다(Q5).
 */
public record ReportCreateRequest(

        @NotNull(message = "신고 대상 종류는 필수입니다")
        ReportTargetType targetType,

        @NotBlank(message = "신고 대상 식별자는 필수입니다")
        @Size(min = PublicIdGenerator.LENGTH, max = PublicIdGenerator.LENGTH,
                message = "올바른 신고 대상 식별자가 아닙니다")
        String targetId,

        @NotNull(message = "신고 사유는 필수입니다")
        ReportReason reasonCode,

        @Size(max = 1000, message = "신고 상세 내용은 1,000자 이하여야 합니다")
        String detail,

        Long evidenceMessageId
) {
}
