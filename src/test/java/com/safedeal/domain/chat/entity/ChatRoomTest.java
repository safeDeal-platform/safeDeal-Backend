package com.safedeal.domain.chat.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ChatRoomTest {

    @Test
    @DisplayName("open()은 전달한 값 그대로 필드를 채우고, 숨김 시각은 둘 다 null이다")
    void open_mapsFieldsAndStartsVisible() {
        ChatRoom room = ChatRoom.open("01J3ARSNIPROOM0000000001", 10L, 20L, 30L);

        assertThat(room.getPublicId()).isEqualTo("01J3ARSNIPROOM0000000001");
        assertThat(room.getListingId()).isEqualTo(10L);
        assertThat(room.getBuyerId()).isEqualTo(20L);
        assertThat(room.getSellerId()).isEqualTo(30L);
        assertThat(room.getBuyerHiddenAt()).isNull();
        assertThat(room.getSellerHiddenAt()).isNull();
    }

    @Test
    @DisplayName("rejoinAsBuyer()는 숨겨져 있던 buyerHiddenAt만 null로 되돌린다")
    void rejoinAsBuyer_clearsOnlyBuyerHidden() {
        ChatRoom room = ChatRoom.open("01J3ARSNIPROOM0000000001", 10L, 20L, 30L);
        ReflectionTestUtils.setField(room, "buyerHiddenAt", Instant.parse("2026-09-01T00:00:00Z"));
        ReflectionTestUtils.setField(room, "sellerHiddenAt", Instant.parse("2026-09-02T00:00:00Z"));

        room.rejoinAsBuyer();

        assertThat(room.getBuyerHiddenAt()).isNull();
        assertThat(room.getSellerHiddenAt()).isEqualTo(Instant.parse("2026-09-02T00:00:00Z"));
    }

    @Test
    @DisplayName("이미 보이는 방에 rejoinAsBuyer()를 다시 호출해도 그대로 null이다 (멱등)")
    void rejoinAsBuyer_isIdempotentWhenAlreadyVisible() {
        ChatRoom room = ChatRoom.open("01J3ARSNIPROOM0000000001", 10L, 20L, 30L);

        room.rejoinAsBuyer();

        assertThat(room.getBuyerHiddenAt()).isNull();
    }
}
