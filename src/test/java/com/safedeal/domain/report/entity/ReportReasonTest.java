package com.safedeal.domain.report.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 사유×대상 매트릭스가 정책과 어긋나면 엉뚱한 신고가 접수된다. 정책 표를 그대로 고정한다. */
class ReportReasonTest {

    @Test
    @DisplayName("사기는 매물에만 붙는다 - 채팅방은 사기 대상이 아니다")
    void fraudOnlyForListing() {
        assertThat(ReportReason.FRAUD.allowsFor(ReportTargetType.LISTING)).isTrue();
        assertThat(ReportReason.FRAUD.allowsFor(ReportTargetType.CHAT)).isFalse();
    }

    @Test
    @DisplayName("금지품목도 매물에만 붙는다")
    void prohibitedOnlyForListing() {
        assertThat(ReportReason.PROHIBITED.allowsFor(ReportTargetType.LISTING)).isTrue();
        assertThat(ReportReason.PROHIBITED.allowsFor(ReportTargetType.CHAT)).isFalse();
    }

    @Test
    @DisplayName("욕설·괴롭힘은 채팅에만 붙는다 - 매물 설명은 대상이 아니다")
    void abuseOnlyForChat() {
        assertThat(ReportReason.ABUSE.allowsFor(ReportTargetType.CHAT)).isTrue();
        assertThat(ReportReason.ABUSE.allowsFor(ReportTargetType.LISTING)).isFalse();
    }

    @Test
    @DisplayName("스팸·사칭·기타는 매물과 채팅 둘 다에 붙는다")
    void spamImpersonationEtcForBoth() {
        for (ReportReason reason : new ReportReason[]{
                ReportReason.SPAM, ReportReason.IMPERSONATION, ReportReason.ETC}) {
            assertThat(reason.allowsFor(ReportTargetType.LISTING)).as(reason.name()).isTrue();
            assertThat(reason.allowsFor(ReportTargetType.CHAT)).as(reason.name()).isTrue();
        }
    }

    @Test
    @DisplayName("대상이 없으면(null) 어떤 사유도 허용하지 않는다")
    void nullTargetAllowsNothing() {
        for (ReportReason reason : ReportReason.values()) {
            assertThat(reason.allowsFor(null)).as(reason.name()).isFalse();
        }
    }
}
