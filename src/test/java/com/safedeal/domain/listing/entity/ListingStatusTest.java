package com.safedeal.domain.listing.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 전이표가 정책과 어긋나면 잘못된 상태 변경이 코드 리뷰를 통과해버린다. 여기서 고정한다. */
class ListingStatusTest {

    @Test
    @DisplayName("검증을 거치는 경로와 건너뛰는 경로 둘 다 허용한다")
    void draftPaths() {
        assertThat(ListingStatus.DRAFT.canTransitionTo(ListingStatus.PENDING_VERIFICATION)).isTrue();
        assertThat(ListingStatus.DRAFT.canTransitionTo(ListingStatus.ACTIVE)).isTrue();
        assertThat(ListingStatus.DRAFT.canTransitionTo(ListingStatus.SOLD)).isFalse();
    }

    @Test
    @DisplayName("이미지 교체로 공개 매물이 검증 대기로 되돌아갈 수 있다")
    void activeCanRegressToVerification() {
        assertThat(ListingStatus.ACTIVE.canTransitionTo(ListingStatus.PENDING_VERIFICATION)).isTrue();
    }

    @Test
    @DisplayName("판매완료는 되돌릴 수 있다 - 수동 실수와 환불 복구 경로")
    void soldCanReturnToActive() {
        assertThat(ListingStatus.SOLD.canTransitionTo(ListingStatus.ACTIVE)).isTrue();
    }

    @Test
    @DisplayName("차단은 재검증으로 풀린다")
    void blockedCanReturnToActive() {
        assertThat(ListingStatus.BLOCKED.canTransitionTo(ListingStatus.ACTIVE)).isTrue();
    }

    @Test
    @DisplayName("삭제는 종착점이라 어디로도 나가지 않는다")
    void deletedIsTerminal() {
        for (ListingStatus target : ListingStatus.values()) {
            assertThat(ListingStatus.DELETED.canTransitionTo(target)).isFalse();
        }
    }

    @Test
    @DisplayName("공개되는 상태는 ACTIVE 하나뿐이다")
    void onlyActiveIsPublic() {
        for (ListingStatus s : ListingStatus.values()) {
            assertThat(s.isPubliclyVisible()).isEqualTo(s == ListingStatus.ACTIVE);
        }
    }

    @Test
    @DisplayName("수정은 ACTIVE와 검증 대기에서만 된다")
    void editableStates() {
        assertThat(ListingStatus.ACTIVE.isEditable()).isTrue();
        assertThat(ListingStatus.PENDING_VERIFICATION.isEditable()).isTrue();
        assertThat(ListingStatus.SOLD.isEditable()).isFalse();
        assertThat(ListingStatus.BLOCKED.isEditable()).isFalse();
        assertThat(ListingStatus.DELETED.isEditable()).isFalse();
    }

    @Test
    @DisplayName("null 전이는 허용하지 않는다")
    void nullTargetRejected() {
        assertThat(ListingStatus.ACTIVE.canTransitionTo(null)).isFalse();
    }
}
