package com.safedeal.domain.chat.service;

import com.safedeal.domain.chat.dto.ChatMessageAppendCommand;
import com.safedeal.domain.chat.dto.ChatMessageAppendResult;
import com.safedeal.domain.chat.entity.ChatMessage;
import com.safedeal.domain.chat.entity.ChatRoom;
import com.safedeal.domain.chat.repository.ChatMessageRepository;
import com.safedeal.domain.chat.repository.ChatRoomRepository;
import com.safedeal.global.exception.BusinessException;
import com.safedeal.global.exception.CommonErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatMessageAppenderTest {

    @Mock
    ChatMessageRepository chatMessageRepository;

    @Mock
    ChatRoomRepository chatRoomRepository;

    @InjectMocks
    ChatMessageAppender chatMessageAppender;

    private static final Long ROOM_ID = 10L;
    private static final Long BUYER_ID = 20L;
    private static final Long SELLER_ID = 30L;
    private static final String ROOM_PUBLIC_ID = "01J3ARSNIPROOM0000000001";

    private static ChatRoom room() {
        ChatRoom room = ChatRoom.open(ROOM_PUBLIC_ID, 99L, BUYER_ID, SELLER_ID);
        ReflectionTestUtils.setField(room, "id", ROOM_ID);
        return room;
    }

    private static ChatMessage message(long id, Long senderId) {
        ChatMessage message = ChatMessage.write(ROOM_ID, senderId, "안녕하세요", "c-uuid-" + id);
        ReflectionTestUtils.setField(message, "id", id);
        ReflectionTestUtils.setField(message, "createdAt", Instant.parse("2026-09-22T00:00:00Z"));
        return message;
    }

    @Test
    @DisplayName("방이 없거나 참여자가 아니면 404 C002 (구분하지 않는다)")
    void append_roomMissingOrNotParticipant_throwsNotFound() {
        when(chatRoomRepository.findByPublicIdAndParticipant(ROOM_PUBLIC_ID, 999L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatMessageAppender.append(999L,
                new ChatMessageAppendCommand(ROOM_PUBLIC_ID, "안녕", "c-uuid-1")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
        verify(chatMessageRepository, never()).save(any());
    }

    @Test
    @DisplayName("새 메시지면 저장하고, 발신자가 구매자면 구매자 커서를 갱신한다")
    void append_newMessage_savesAndAdvancesBuyerCursor() {
        ChatRoom room = room();
        when(chatRoomRepository.findByPublicIdAndParticipant(ROOM_PUBLIC_ID, BUYER_ID))
                .thenReturn(Optional.of(room));
        when(chatMessageRepository.findByRoomIdAndClientMessageId(ROOM_ID, "c-uuid-1"))
                .thenReturn(Optional.empty());
        when(chatMessageRepository.save(any(ChatMessage.class)))
                .thenAnswer(invocation -> {
                    ChatMessage m = invocation.getArgument(0);
                    ReflectionTestUtils.setField(m, "id", 100L);
                    ReflectionTestUtils.setField(m, "createdAt", Instant.parse("2026-09-22T00:00:00Z"));
                    return m;
                });

        ChatMessageAppendResult result = chatMessageAppender.append(BUYER_ID,
                new ChatMessageAppendCommand(ROOM_PUBLIC_ID, "안녕하세요", "c-uuid-1"));

        assertThat(result.messageId()).isEqualTo(100L);
        assertThat(result.roomId()).isEqualTo(ROOM_PUBLIC_ID);
        assertThat(result.senderId()).isEqualTo(BUYER_ID);
        assertThat(result.content()).isEqualTo("안녕하세요");
        verify(chatRoomRepository).markBuyerRead(eq(ROOM_ID), eq(100L), any(Instant.class));
        verify(chatRoomRepository, never()).markSellerRead(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("발신자가 판매자면 판매자 커서를 갱신한다")
    void append_newMessage_sellerSender_advancesSellerCursor() {
        ChatRoom room = room();
        when(chatRoomRepository.findByPublicIdAndParticipant(ROOM_PUBLIC_ID, SELLER_ID))
                .thenReturn(Optional.of(room));
        when(chatMessageRepository.findByRoomIdAndClientMessageId(ROOM_ID, "c-uuid-1"))
                .thenReturn(Optional.empty());
        when(chatMessageRepository.save(any(ChatMessage.class)))
                .thenAnswer(invocation -> {
                    ChatMessage m = invocation.getArgument(0);
                    ReflectionTestUtils.setField(m, "id", 101L);
                    ReflectionTestUtils.setField(m, "createdAt", Instant.parse("2026-09-22T00:00:00Z"));
                    return m;
                });

        chatMessageAppender.append(SELLER_ID,
                new ChatMessageAppendCommand(ROOM_PUBLIC_ID, "네 안녕하세요", "c-uuid-1"));

        verify(chatRoomRepository).markSellerRead(eq(ROOM_ID), eq(101L), any(Instant.class));
        verify(chatRoomRepository, never()).markBuyerRead(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("재전송(같은 clientMessageId)이면 기존 메시지를 그대로 반환하고, 저장·커서 갱신 모두 건너뛴다")
    void append_resend_returnsExistingWithoutSideEffects() {
        ChatRoom room = room();
        ChatMessage existing = message(100L, BUYER_ID);
        when(chatRoomRepository.findByPublicIdAndParticipant(ROOM_PUBLIC_ID, BUYER_ID))
                .thenReturn(Optional.of(room));
        when(chatMessageRepository.findByRoomIdAndClientMessageId(ROOM_ID, "c-uuid-100"))
                .thenReturn(Optional.of(existing));

        ChatMessageAppendResult result = chatMessageAppender.append(BUYER_ID,
                new ChatMessageAppendCommand(ROOM_PUBLIC_ID, "안녕하세요", "c-uuid-100"));

        assertThat(result.messageId()).isEqualTo(100L);
        verify(chatMessageRepository, never()).save(any());
        verify(chatRoomRepository, never()).markBuyerRead(anyLong(), anyLong(), any());
        verify(chatRoomRepository, never()).markSellerRead(anyLong(), anyLong(), any());
    }
}
