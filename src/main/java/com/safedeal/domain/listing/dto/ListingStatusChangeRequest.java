package com.safedeal.domain.listing.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 판매자 수동 상태 전이.
 *
 * <p>구매자용 전이는 두지 않는다 — 채팅만 걸어둔 제3자가 남의 공개 매물을 판매완료로 잠글 수
 * 있다. 판매자가 거짓으로 누르는 건 자기 물건을 못 파는 자기 손해라 피해자가 없다.
 */
public record ListingStatusChangeRequest(@NotNull(message = "action은 필수입니다") Action action) {

    public enum Action {
        /** 플랫폼 밖 직거래로 팔렸을 때. 통계·거래기록에는 반영하지 않는다. */
        MARK_SOLD,
        /** 실수로 누른 판매완료 되돌리기. 24시간 안에만 가능하다. */
        RESTORE_MANUAL_SOLD
    }
}
