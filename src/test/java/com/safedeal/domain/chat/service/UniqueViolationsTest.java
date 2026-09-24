package com.safedeal.domain.chat.service;

import com.safedeal.domain.chat.entity.ChatMessage;
import com.safedeal.domain.chat.entity.ChatRoom;
import com.safedeal.domain.chat.repository.ChatMessageRepository;
import com.safedeal.domain.chat.repository.ChatRoomRepository;
import com.safedeal.testsupport.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 재시도 판정({@link UniqueViolations})이 <b>실제 MySQL</b>이 돌려주는 예외로 제약을 정확히
 * 가르는지 고정한다. 단위 테스트의 가짜 예외({@link UniqueViolationFixtures})가 실제와 같은
 * 모양이라는 근거가 이 테스트다. 위반된 제약의 이름을 <b>정확한 문자열</b>로 단언하므로,
 * MySQL 이미지를 올려 표기(예: {@code 테이블.제약})가 바뀌면 여기서 먼저 깨진다.
 */
@Transactional
class UniqueViolationsTest extends IntegrationTestSupport {

    @Autowired
    ChatRoomRepository chatRoomRepository;

    @Autowired
    ChatMessageRepository chatMessageRepository;

    @Test
    @DisplayName("(buyer_id, listing_id) 중복 — 위반 제약 이름이 정확히 chat_rooms.uk_chat_rooms_buyer_listing이다")
    void buyerListingDuplicate_reportsExactConstraintName() {
        chatRoomRepository.saveAndFlush(ChatRoom.open("01J3ARSNIPROOM0000000001", 10L, 20L, 30L));

        Throwable error = catchThrowable(() -> chatRoomRepository.saveAndFlush(
                ChatRoom.open("01J3ARSNIPROOM0000000002", 10L, 20L, 30L)));

        assertThat(error).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(UniqueViolations.violatedConstraint(error))
                .isEqualTo("chat_rooms.uk_chat_rooms_buyer_listing");
        assertThat(UniqueViolations.causedBy(error, ChatRoom.UK_BUYER_LISTING)).isTrue();
        assertThat(UniqueViolations.causedBy(error, ChatRoom.UK_PUBLIC_ID)).isFalse();
    }

    @Test
    @DisplayName("public_id 중복 — 위반 제약 이름이 정확히 chat_rooms.uk_chat_rooms_public_id이고 buyer_listing으로 오인하지 않는다")
    void publicIdDuplicate_reportsExactConstraintName() {
        chatRoomRepository.saveAndFlush(ChatRoom.open("01J3ARSNIPROOM0000000001", 10L, 20L, 30L));

        Throwable error = catchThrowable(() -> chatRoomRepository.saveAndFlush(
                ChatRoom.open("01J3ARSNIPROOM0000000001", 11L, 21L, 30L)));

        assertThat(error).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(UniqueViolations.violatedConstraint(error))
                .isEqualTo("chat_rooms.uk_chat_rooms_public_id");
        assertThat(UniqueViolations.causedBy(error, ChatRoom.UK_PUBLIC_ID)).isTrue();
        assertThat(UniqueViolations.causedBy(error, ChatRoom.UK_BUYER_LISTING)).isFalse();
    }

    @Test
    @DisplayName("(room_id, client_message_id) 중복 — 위반 제약 이름이 정확히 chat_messages.uk_chat_messages_room_client_msg이다")
    void clientMessageDuplicate_reportsExactConstraintName() {
        chatMessageRepository.saveAndFlush(ChatMessage.write(10L, 20L, "안녕", "c-uuid-1"));

        Throwable error = catchThrowable(() -> chatMessageRepository.saveAndFlush(
                ChatMessage.write(10L, 20L, "또 안녕", "c-uuid-1")));

        assertThat(error).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(UniqueViolations.violatedConstraint(error))
                .isEqualTo("chat_messages.uk_chat_messages_room_client_msg");
        assertThat(UniqueViolations.causedBy(error, ChatMessage.UK_ROOM_CLIENT_MSG)).isTrue();
        assertThat(UniqueViolations.causedBy(error, ChatRoom.UK_BUYER_LISTING)).isFalse();
    }

    @Test
    @DisplayName("clientMessageId에 ' for key ' 구분자와 다른 제약 이름을 심어도 진짜 위반 제약으로 판정된다")
    void craftedClientMessageIdWithDelimiter_cannotSpoofTheDecision() {
        // 진짜 MySQL 메시지는 "Duplicate entry '10-z' for key 'uk_chat_rooms_buyer_listing' for key
        // 'chat_messages.uk_chat_messages_room_client_msg'" 꼴이 된다 — 사용자 값이 서버가 붙이는
        // 꼬리 앞에 실린다. 39자라 ChatMessage.write()의 64자 검증을 통과하는 값이다.
        String spoof = "z' for key '" + ChatRoom.UK_BUYER_LISTING;
        chatMessageRepository.saveAndFlush(ChatMessage.write(10L, 20L, "안녕", spoof));

        Throwable error = catchThrowable(() -> chatMessageRepository.saveAndFlush(
                ChatMessage.write(10L, 20L, "또 안녕", spoof)));

        assertThat(error).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(UniqueViolations.violatedConstraint(error))
                .isEqualTo("chat_messages.uk_chat_messages_room_client_msg");
        assertThat(UniqueViolations.causedBy(error, ChatMessage.UK_ROOM_CLIENT_MSG)).isTrue();
        assertThat(UniqueViolations.causedBy(error, ChatRoom.UK_BUYER_LISTING)).isFalse();
    }
}
