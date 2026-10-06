package com.safedeal.domain.report.entity;

import com.safedeal.domain.report.exception.ReportErrorCode;
import com.safedeal.global.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 엔티티 팩토리가 사유×대상 매트릭스를 직접 막는지 고정한다. 서비스가 검증을 빠뜨려도 뚫리면 안 된다. */
class ReportTest {

    private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");

    @Test
    @DisplayName("사기 사유를 채팅방에 붙이면 RPT004로 거절하고 행을 만들지 않는다")
    void fraudOnChatIsRejected() {
        assertThatThrownBy(() -> Report.receive("01J3ARSNIPREPORT0000000001", 20L,
                ReportTargetType.CHAT, 10L, "01J3ARSNIPROOM00000000001", 30L,
                ReportReason.FRAUD, null, 5L, NOW))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ReportErrorCode.REASON_NOT_ALLOWED_FOR_TARGET));
    }

    @Test
    @DisplayName("허용된 조합은 접수 상태(RECEIVED)로 만들어지고 접수 시각을 가진다")
    void allowedCombinationStartsReceived() {
        Report report = Report.receive("01J3ARSNIPREPORT0000000001", 20L,
                ReportTargetType.LISTING, 10L, "01J3ARSNIPLISTING000001", 30L,
                ReportReason.FRAUD, "허위 매물입니다", null, NOW);

        assertThat(report.getStatus()).isEqualTo(ReportStatus.RECEIVED);
        assertThat(report.getReceivedAt()).isEqualTo(NOW);
        assertThat(report.getAccusedUserId()).isEqualTo(30L);
    }
}
