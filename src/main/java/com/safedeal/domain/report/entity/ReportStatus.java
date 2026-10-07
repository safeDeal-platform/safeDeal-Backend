package com.safedeal.domain.report.entity;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 신고 상태와 허용 전이. {@code ListingStatus}와 같은 방식이다: 전이표는 "정해진 길만 연다"는 규칙이고,
 * 동시성의 최종 심판은 조건부 UPDATE({@code WHERE status = 기대값})다.
 *
 * <p>{@code EnumType.STRING}으로 저장한다.
 *
 * <p>전이표는 확정 전 기본값이다: 접수 상태에서 바로 처리·기각으로 가는 것을 허용하고, 검토 중에는
 * 신고자가 취소할 수 없다. 이 두 칸은 정책 확정 때 다시 확인한다. 검토 중 취소 금지는
 * {@code ReportCommandService.cancel}이 조건부 UPDATE로 강제한다(사용자 확정 2026-10-05: REVIEWING 이후는 409).
 */
public enum ReportStatus {

    /** 접수됨. 같은 사유의 재신고는 이 상태가 살아 있는 동안 RPT001로 거절된다. */
    RECEIVED,

    /** 관리자 검토 중. */
    REVIEWING,

    /** 제재로 종결. 이후 상태는 바뀌지 않는다. */
    RESOLVED,

    /** 기각으로 종결. 이후 상태는 바뀌지 않는다. */
    REJECTED,

    /** 신고자가 취소. 같은 사유로 다시 신고하면 같은 행이 RECEIVED로 되살아난다. */
    CANCELLED;

    private static final Map<ReportStatus, Set<ReportStatus>> ALLOWED =
            new EnumMap<>(ReportStatus.class);

    static {
        ALLOWED.put(RECEIVED, EnumSet.of(REVIEWING, RESOLVED, REJECTED, CANCELLED));
        ALLOWED.put(REVIEWING, EnumSet.of(RESOLVED, REJECTED));
        ALLOWED.put(CANCELLED, EnumSet.of(RECEIVED));
        ALLOWED.put(RESOLVED, EnumSet.noneOf(ReportStatus.class));
        ALLOWED.put(REJECTED, EnumSet.noneOf(ReportStatus.class));
    }

    public boolean canTransitionTo(ReportStatus target) {
        return target != null && ALLOWED.get(this).contains(target);
    }
}
