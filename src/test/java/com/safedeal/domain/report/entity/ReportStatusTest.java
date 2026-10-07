package com.safedeal.domain.report.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 전이표가 정책과 어긋나면 종결된 신고가 되살아나거나 검토 중인 신고가 사라진다. 여기서 고정한다. */
class ReportStatusTest {

    @Test
    @DisplayName("접수된 신고는 검토·처리·기각·취소로 갈 수 있다")
    void receivedCanGoToEveryNextStep() {
        assertThat(ReportStatus.RECEIVED.canTransitionTo(ReportStatus.REVIEWING)).isTrue();
        assertThat(ReportStatus.RECEIVED.canTransitionTo(ReportStatus.RESOLVED)).isTrue();
        assertThat(ReportStatus.RECEIVED.canTransitionTo(ReportStatus.REJECTED)).isTrue();
        assertThat(ReportStatus.RECEIVED.canTransitionTo(ReportStatus.CANCELLED)).isTrue();
    }

    @Test
    @DisplayName("검토 중에는 처리 또는 기각만 된다 - 검토 중 취소는 막는다(기본값)")
    void reviewingOnlyResolvesOrRejects() {
        assertThat(ReportStatus.REVIEWING.canTransitionTo(ReportStatus.RESOLVED)).isTrue();
        assertThat(ReportStatus.REVIEWING.canTransitionTo(ReportStatus.REJECTED)).isTrue();
        assertThat(ReportStatus.REVIEWING.canTransitionTo(ReportStatus.CANCELLED)).isFalse();
        assertThat(ReportStatus.REVIEWING.canTransitionTo(ReportStatus.RECEIVED)).isFalse();
    }

    @Test
    @DisplayName("취소된 신고는 같은 행이 접수 상태로 되살아날 수 있다 - 같은 사유 재신고 경로")
    void cancelledCanRevive() {
        assertThat(ReportStatus.CANCELLED.canTransitionTo(ReportStatus.RECEIVED)).isTrue();
        assertThat(ReportStatus.CANCELLED.canTransitionTo(ReportStatus.RESOLVED)).isFalse();
    }

    @Test
    @DisplayName("처리와 기각은 종착점이라 어디로도 나가지 않는다")
    void resolvedAndRejectedAreTerminal() {
        for (ReportStatus target : ReportStatus.values()) {
            assertThat(ReportStatus.RESOLVED.canTransitionTo(target)).as("RESOLVED->" + target).isFalse();
            assertThat(ReportStatus.REJECTED.canTransitionTo(target)).as("REJECTED->" + target).isFalse();
        }
    }

    @Test
    @DisplayName("null 전이는 허용하지 않는다")
    void nullTargetRejected() {
        assertThat(ReportStatus.RECEIVED.canTransitionTo(null)).isFalse();
    }
}
