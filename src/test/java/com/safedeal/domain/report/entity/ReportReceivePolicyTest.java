package com.safedeal.domain.report.entity;

import com.safedeal.global.exception.BusinessException;
import com.safedeal.global.exception.CommonErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 신고 접수 API(POST /api/reports)에서 확정된 정책 중 엔티티 팩토리가 직접 막아야 하는 것(Q5·Q6·D4).
 * 서비스가 검증을 빠뜨려도 행이 잘못 저장되지 않게 여기서 고정한다.
 */
class ReportReceivePolicyTest {

    private static final Instant NOW = Instant.parse("2026-11-15T00:00:00Z");

    private static Report listingReport(String detail, Long evidenceMessageId) {
        return Report.receive("01J3ARSNIPREPORT0000000001", 20L,
                ReportTargetType.LISTING, 10L, "01J3ARSNIPLISTING000000001", 30L,
                ReportReason.FRAUD, detail, evidenceMessageId, NOW);
    }

    @Test
    @DisplayName("RPT-C15 매물 신고에 증거 메시지 id가 있으면 400(C001)으로 거절한다 (Q5)")
    void listingReportWithEvidenceIsRejected() {
        assertThatThrownBy(() -> listingReport(null, 5L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(CommonErrorCode.INVALID_INPUT));
    }

    @Test
    @DisplayName("RPT-C23 상세 내용이 빈 문자열이면 null로 저장한다 (Q6)")
    void emptyDetailBecomesNull() {
        assertThat(listingReport("", null).getDetail()).isNull();
    }

    @Test
    @DisplayName("RPT-C23 상세 내용이 공백뿐이면 strip 후 비므로 null로 저장한다 (Q6)")
    void blankDetailBecomesNull() {
        assertThat(listingReport("  \t\n ", null).getDetail()).isNull();
    }

    @Test
    @DisplayName("RPT-C48 글자가 있으면 앞뒤 공백까지 원문 그대로 저장한다 - strip하지 않는다 (D4)")
    void nonBlankDetailIsKeptAsIs() {
        assertThat(listingReport("  허위 매물\n", null).getDetail()).isEqualTo("  허위 매물\n");
    }

    @Test
    @DisplayName("RPT-C48 1,000자 검사는 원문 기준이다 - 앞 공백 포함 1,001자면 400(C001) (D4)")
    void lengthIsCheckedOnRawDetail() {
        assertThatThrownBy(() -> listingReport(" " + "가".repeat(1000), null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(CommonErrorCode.INVALID_INPUT));
    }
}
