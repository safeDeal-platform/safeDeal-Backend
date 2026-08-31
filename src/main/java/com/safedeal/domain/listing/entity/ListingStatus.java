package com.safedeal.domain.listing.entity;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 매물 상태와 허용 전이.
 *
 * <p>전이표는 "정의한 상태로만 갈 수 있게" 규칙을 한 곳에 모으는 장치이지 동시성 방어가
 * 아니다. 서버 두 대가 동시에 서로 다른 전이를 시도하면 각자 자기 메모리에서 검증해 둘 다
 * 통과한다. <b>최종 심판은 조건부 UPDATE</b>(WHERE status=기대값)이고, 영향 행이 0이면 다른
 * 전이가 선점했다는 뜻이다.
 *
 * <p>{@code EnumType.STRING}으로 저장한다 — ORDINAL은 상수 순서가 바뀌는 순간 기존 행의
 * 의미가 통째로 달라진다.
 */
public enum ListingStatus {

    /** 등록 직후. 검증 flag가 꺼져 있으면 이 상태에 머물지 않고 곧바로 ACTIVE로 간다. */
    DRAFT,

    /** 검증 대기·진행. 검증 도메인의 상태가 아니라 매물 쪽에서 본 "아직 공개 못 함"이다. */
    PENDING_VERIFICATION,

    /** 공개. 목록·검색에 노출되는 유일한 상태다. */
    ACTIVE,

    /** 판매 완료. 결제 이벤트 또는 판매자 버튼으로 진입한다. */
    SOLD,

    /** 운영자 제재로 내려간 상태. 물리 삭제하지 않는다 — 신고·거래 이력의 근거가 사라진다. */
    BLOCKED,

    /** 소프트 삭제. 행은 남고 목록에서만 빠진다. */
    DELETED;

    private static final Map<ListingStatus, Set<ListingStatus>> ALLOWED =
            new EnumMap<>(ListingStatus.class);

    static {
        // 정책이 명시한 전이만 넣는다 — "그 외 전이 금지"가 원칙이라, 있으면 편할 것 같은
        // 전이를 미리 열어두지 않는다.
        ALLOWED.put(DRAFT, EnumSet.of(PENDING_VERIFICATION, ACTIVE));
        // 검증 점수가 차단 구간이면 공개되지 못하고 곧바로 차단된다.
        ALLOWED.put(PENDING_VERIFICATION, EnumSet.of(ACTIVE, BLOCKED));
        // 이미지를 바꾸면 낡은 승인이 그대로 붙는 것을 막으려 검증 대기로 되돌린다.
        ALLOWED.put(ACTIVE, EnumSet.of(SOLD, BLOCKED, DELETED, PENDING_VERIFICATION));
        // 수동 SOLD 되돌리기(24시간)와 결제 환불 복구가 이 경로를 쓴다.
        ALLOWED.put(SOLD, EnumSet.of(ACTIVE, DELETED));
        // 재검증을 통과하면 다시 공개된다. DELETED로는 내리지 않는다 — 제재 건을 지우면
        // 거래 스냅샷·주문·신고 이력이 고아가 되고 이의제기 시 근거가 사라진다.
        ALLOWED.put(BLOCKED, EnumSet.of(ACTIVE));
        // 삭제는 종착점이다.
        ALLOWED.put(DELETED, EnumSet.noneOf(ListingStatus.class));
    }

    public boolean canTransitionTo(ListingStatus target) {
        return target != null && ALLOWED.get(this).contains(target);
    }

    /**
     * 목록·검색에 노출되는 상태인지. 거부 목록이 아니라 허용 목록으로 판단한다 — 상태가
     * 추가돼도 노출 사고가 나지 않는다.
     */
    public boolean isListable() {
        return this == ACTIVE;
    }

    /**
     * 상세 조회로 열어주는 상태인지.
     *
     * <p>목록과 기준이 다르다 — 팔린 매물은 목록에서 빠지지만 상세는 열려 있어야 한다.
     * 거래 당사자가 나중에 무엇을 샀는지 확인하고, 채팅·신고에서 넘어온 링크가 죽지 않아야
     * 하기 때문이다. 차단·삭제만 없는 것으로 취급한다.
     */
    public boolean isViewable() {
        return this == ACTIVE || this == SOLD;
    }

    /** 내용 수정이 허용되는 상태인지. */
    public boolean isEditable() {
        return this == ACTIVE || this == PENDING_VERIFICATION;
    }
}
