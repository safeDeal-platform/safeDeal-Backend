package com.safedeal.domain.chat.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatRoomTest {

    @Test
    @DisplayName("open()은 전달한 값 그대로 필드를 채우고, 숨김 시각은 null·읽음 커서는 0으로 시작한다")
    void open_mapsFieldsAndStartsVisibleWithZeroCursors() {
        ChatRoom room = ChatRoom.open("01J3ARSNIPROOM0000000001", 10L, 20L, 30L);

        assertThat(room.getPublicId()).isEqualTo("01J3ARSNIPROOM0000000001");
        assertThat(room.getListingId()).isEqualTo(10L);
        assertThat(room.getBuyerId()).isEqualTo(20L);
        assertThat(room.getSellerId()).isEqualTo(30L);
        assertThat(room.getBuyerHiddenAt()).isNull();
        assertThat(room.getSellerHiddenAt()).isNull();
        // 0이어야 한다 — null이면 조건부 UPDATE의 "< 새값" 비교가 UNKNOWN이 돼 첫 갱신이 영원히 실패한다.
        assertThat(room.getBuyerLastReadMessageId()).isZero();
        assertThat(room.getSellerLastReadMessageId()).isZero();
    }

    @Test
    @DisplayName("구매자·판매자는 각자 참여자로 판정되고, 제3자는 아니다")
    void participantChecks_distinguishBuyerSellerAndOutsider() {
        ChatRoom room = ChatRoom.open("01J3ARSNIPROOM0000000001", 10L, 20L, 30L);

        assertThat(room.isBuyer(20L)).isTrue();
        assertThat(room.isSeller(20L)).isFalse();
        assertThat(room.isSeller(30L)).isTrue();
        assertThat(room.isBuyer(30L)).isFalse();
        assertThat(room.isParticipant(20L)).isTrue();
        assertThat(room.isParticipant(30L)).isTrue();
        assertThat(room.isParticipant(999L)).isFalse();
    }
}
