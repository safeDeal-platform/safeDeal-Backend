package com.safedeal.domain.report.dto;

import com.safedeal.domain.report.entity.ReportStatus;

import java.time.Instant;

/**
 * 신고 접수 결과. 새 신고와 취소 신고 부활 모두 같은 모양이다(Q1). 피신고자·내부 id는 싣지 않는다(RPT-C30).
 *
 * @param reportId 신고의 공개 id(ULID). 취소 API 경로에 그대로 쓴다.
 */
public record ReportCreateResponse(String reportId, ReportStatus status, Instant receivedAt) {
}
