package com.safedeal.domain.report.entity;

import java.util.EnumSet;
import java.util.Set;

/**
 * 신고 사유 6종과, 사유마다 신고할 수 있는 대상. 정책의 "사유×대상 매트릭스"를 그대로 옮겼다.
 * 매트릭스 밖의 조합은 {@code RPT004}(400)로 거절한다.
 *
 * <p>사유가 UNIQUE 제약의 구성 요소라, 이 목록을 바꾸면 기존 데이터의 중복 판정 뜻이 달라진다 — 목록은 확정된 것만 둔다.
 */
public enum ReportReason {

    FRAUD(EnumSet.of(ReportTargetType.LISTING)),
    PROHIBITED(EnumSet.of(ReportTargetType.LISTING)),
    SPAM(EnumSet.of(ReportTargetType.LISTING, ReportTargetType.CHAT)),
    ABUSE(EnumSet.of(ReportTargetType.CHAT)),
    IMPERSONATION(EnumSet.of(ReportTargetType.LISTING, ReportTargetType.CHAT)),
    ETC(EnumSet.of(ReportTargetType.LISTING, ReportTargetType.CHAT));

    private final Set<ReportTargetType> allowedTargets;

    ReportReason(Set<ReportTargetType> allowedTargets) {
        this.allowedTargets = allowedTargets;
    }

    /** 이 사유를 해당 대상 종류에 붙일 수 있는지. */
    public boolean allowsFor(ReportTargetType target) {
        return target != null && allowedTargets.contains(target);
    }
}
