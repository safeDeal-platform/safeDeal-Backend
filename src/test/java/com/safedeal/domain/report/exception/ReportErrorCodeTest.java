package com.safedeal.domain.report.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 공개한 에러 코드는 이름·번호·상태가 바뀌면 클라이언트 분기가 조용히 틀어진다. 여기서 고정한다. */
class ReportErrorCodeTest {

    @Test
    @DisplayName("모든 신고 에러 코드는 RPT 접두어와 3자리 번호를 갖고, 서로 겹치지 않는다")
    void codesHaveUniquePrefixedNumbers() {
        Set<String> seen = new HashSet<>();
        for (ReportErrorCode error : ReportErrorCode.values()) {
            assertThat(error.getCode()).matches("RPT\\d{3}");
            assertThat(seen.add(error.getCode())).as("중복 코드 " + error.getCode()).isTrue();
        }
    }

    @Test
    @DisplayName("사유·대상 불일치 코드는 RPT004 · 400 · REASON_NOT_ALLOWED_FOR_TARGET이다 - 채팅방+사기 같은 경우")
    void reasonNotAllowedForTarget() {
        assertThat(ReportErrorCode.REASON_NOT_ALLOWED_FOR_TARGET.getCode()).isEqualTo("RPT004");
        assertThat(ReportErrorCode.REASON_NOT_ALLOWED_FOR_TARGET.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("같은 사유 중복(접수·검토 중)은 RPT001 · 409로 거절한다 - 멱등 거절")
    void duplicateActiveIsConflict() {
        assertThat(ReportErrorCode.DUPLICATE_ACTIVE_REPORT.getCode()).isEqualTo("RPT001");
        assertThat(ReportErrorCode.DUPLICATE_ACTIVE_REPORT.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("일 10건 한도 초과는 RPT003 · 429로 응답한다")
    void dailyLimitIsTooManyRequests() {
        assertThat(ReportErrorCode.DAILY_REPORT_LIMIT_EXCEEDED.getCode()).isEqualTo("RPT003");
        assertThat(ReportErrorCode.DAILY_REPORT_LIMIT_EXCEEDED.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("본인 매물 자기 신고는 RPT006 · 400으로 막는다")
    void selfReportIsBadRequest() {
        assertThat(ReportErrorCode.SELF_REPORT_NOT_ALLOWED.getCode()).isEqualTo("RPT006");
        assertThat(ReportErrorCode.SELF_REPORT_NOT_ALLOWED.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
