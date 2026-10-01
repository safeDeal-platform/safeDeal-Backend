package com.safedeal.domain.chat.service;

import com.safedeal.domain.chat.dto.ChatReadResponse;
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
class ChatReadCommandServiceTest {

    @Mock
    ChatRoomRepository chatRoomRepository;

    @Mock
    ChatMessageRepository chatMessageRepository;

    @InjectMocks
    ChatReadCommandService chatReadCommandService;

    private static final Long ROOM_ID = 10L;
    private static final Long BUYER_ID = 20L;
    private static final Long SELLER_ID = 30L;
    private static final String ROOM_PUBLIC_ID = "01J3ARSNIPROOM0000000001";

    private static ChatRoom room() {
        ChatRoom room = ChatRoom.open(ROOM_PUBLIC_ID, 99L, BUYER_ID, SELLER_ID);
        ReflectionTestUtils.setField(room, "id", ROOM_ID);
        return room;
    }

    @Test
    @DisplayName("방이 없거나 참여자가 아니면 404 C002 — 메시지 소속 확인·커서 갱신 둘 다 건너뛴다")
    void markAsRead_roomMissingOrNotParticipant_throwsNotFound() {
        when(chatRoomRepository.findByPublicIdAndParticipant(ROOM_PUBLIC_ID, 999L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatReadCommandService.markAsRead(999L, ROOM_PUBLIC_ID, 1024L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
        verify(chatMessageRepository, never()).existsByIdAndRoomId(anyLong(), anyLong());
        verify(chatRoomRepository, never()).markBuyerRead(anyLong(), anyLong(), any());
        verify(chatRoomRepository, never()).markSellerRead(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("messageId가 이 방 소속이 아니면 400 C001 — 커서 갱신은 건너뛴다")
    void markAsRead_messageNotInRoom_throwsInvalidInput() {
        when(chatRoomRepository.findByPublicIdAndParticipant(ROOM_PUBLIC_ID, BUYER_ID))
                .thenReturn(Optional.of(room()));
        when(chatMessageRepository.existsByIdAndRoomId(9999L, ROOM_ID)).thenReturn(false);

        assertThatThrownBy(() -> chatReadCommandService.markAsRead(BUYER_ID, ROOM_PUBLIC_ID, 9999L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(CommonErrorCode.INVALID_INPUT));
        verify(chatRoomRepository, never()).markBuyerRead(anyLong(), anyLong(), any());
        verify(chatRoomRepository, never()).markSellerRead(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("구매자면 구매자 커서만 전진시키고, 정상 전진(1행)이면 재조회 없이 요청값을 그대로 응답한다")
    void markAsRead_buyer_advancesBuyerCursor_andReturnsRequestedValue() {
        when(chatRoomRepository.findByPublicIdAndParticipant(ROOM_PUBLIC_ID, BUYER_ID))
                .thenReturn(Optional.of(room()));
        when(chatMessageRepository.existsByIdAndRoomId(1024L, ROOM_ID)).thenReturn(true);
        when(chatRoomRepository.markBuyerRead(eq(ROOM_ID), eq(1024L), any(Instant.class))).thenReturn(1);

        ChatReadResponse result = chatReadCommandService.markAsRead(BUYER_ID, ROOM_PUBLIC_ID, 1024L);

        assertThat(result.lastReadMessageId()).isEqualTo(1024L);
        verify(chatRoomRepository).markBuyerRead(eq(ROOM_ID), eq(1024L), any(Instant.class));
        verify(chatRoomRepository, never()).markSellerRead(anyLong(), anyLong(), any());
        verify(chatRoomRepository, never()).findForReadCursorRecheck(anyLong());
    }

    @Test
    @DisplayName("판매자면 판매자 커서만 전진시킨다")
    void markAsRead_seller_advancesSellerCursor() {
        when(chatRoomRepository.findByPublicIdAndParticipant(ROOM_PUBLIC_ID, SELLER_ID))
                .thenReturn(Optional.of(room()));
        when(chatMessageRepository.existsByIdAndRoomId(1024L, ROOM_ID)).thenReturn(true);
        when(chatRoomRepository.markSellerRead(eq(ROOM_ID), eq(1024L), any(Instant.class))).thenReturn(1);

        ChatReadResponse result = chatReadCommandService.markAsRead(SELLER_ID, ROOM_PUBLIC_ID, 1024L);

        assertThat(result.lastReadMessageId()).isEqualTo(1024L);
        verify(chatRoomRepository).markSellerRead(eq(ROOM_ID), eq(1024L), any(Instant.class));
        verify(chatRoomRepository, never()).markBuyerRead(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("역행 요청이면 조건부 UPDATE가 0행이어도 예외 없이 끝나고, 재조회한 실제 커서를 응답한다")
    void markAsRead_regressiveRequest_reloadsActualCursorInResponse() {
        ChatRoom room = room();
        when(chatRoomRepository.findByPublicIdAndParticipant(ROOM_PUBLIC_ID, BUYER_ID))
                .thenReturn(Optional.of(room));
        when(chatMessageRepository.existsByIdAndRoomId(10L, ROOM_ID)).thenReturn(true);
        when(chatRoomRepository.markBuyerRead(eq(ROOM_ID), eq(10L), any(Instant.class))).thenReturn(0);

        ChatRoom reloaded = room();
        ReflectionTestUtils.setField(reloaded, "buyerLastReadMessageId", 50L);
        when(chatRoomRepository.findForReadCursorRecheck(ROOM_ID)).thenReturn(Optional.of(reloaded));

        ChatReadResponse result = chatReadCommandService.markAsRead(BUYER_ID, ROOM_PUBLIC_ID, 10L);

        assertThat(result.lastReadMessageId()).isEqualTo(50L);
        verify(chatRoomRepository).markBuyerRead(eq(ROOM_ID), eq(10L), any(Instant.class));
    }

    @Test
    @DisplayName("동시성 회귀: 방 조회 시점엔 안 읽은 것처럼 보였어도(0), 그 사이 다른 요청이 먼저 더 큰 값으로 전진시켰으면(0행) 그 낡은 스냅샷이 아니라 재조회한 실제 최신 커서를 응답한다")
    void markAsRead_concurrentWinnerAlreadyAdvanced_reloadsInsteadOfStaleSnapshot() {
        // 1단계에서 읽은 스냅샷: 아직 0(하지만 이 값은 응답 계산에 더 이상 쓰이지 않는다).
        ChatRoom staleSnapshot = room();
        when(chatRoomRepository.findByPublicIdAndParticipant(ROOM_PUBLIC_ID, BUYER_ID))
                .thenReturn(Optional.of(staleSnapshot));
        when(chatMessageRepository.existsByIdAndRoomId(80L, ROOM_ID)).thenReturn(true);
        // 그 사이 다른 기기의 요청이 이미 200으로 전진시켜 80은 조건(< 80)을 만족 못 해 0행.
        when(chatRoomRepository.markBuyerRead(eq(ROOM_ID), eq(80L), any(Instant.class))).thenReturn(0);

        ChatRoom actualLatest = room();
        ReflectionTestUtils.setField(actualLatest, "buyerLastReadMessageId", 200L);
        when(chatRoomRepository.findForReadCursorRecheck(ROOM_ID)).thenReturn(Optional.of(actualLatest));

        ChatReadResponse result = chatReadCommandService.markAsRead(BUYER_ID, ROOM_PUBLIC_ID, 80L);

        // 스냅샷(0)도, 요청값(80)도 아니라 실제 최신값(200)이어야 한다 — CONC-1 회귀 방지.
        // 이 재조회가 REPEATABLE READ 스냅샷에 안 걸리는지(= 잠금 읽기인지)는 Mockito로는
        // 검증 불가 — 그 증거는 ChatReadCommandServiceIntegrationTest(CONC-2)가 갖고 있다.
        assertThat(result.lastReadMessageId()).isEqualTo(200L);
    }
}
