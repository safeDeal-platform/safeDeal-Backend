package com.safedeal.domain.chat.service;

import com.safedeal.domain.chat.dto.ChatRoomCreateRequest;
import com.safedeal.domain.chat.dto.ChatRoomCreateResponse;
import com.safedeal.domain.chat.entity.ChatRoom;
import com.safedeal.domain.listing.entity.ListingStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 동시 클릭 재시도 구조를 검증한다. 실제 검증 로직은 {@link ChatRoomCreator}(별도 테스트)에
 * 있고, 여기서는 트랜잭션 경계 밖 재시도만 본다.
 */
@ExtendWith(MockitoExtension.class)
class ChatRoomCommandServiceTest {

    @Mock
    ChatRoomCreator chatRoomCreator;

    @InjectMocks
    ChatRoomCommandService chatRoomCommandService;

    private static final Long BUYER_ID = 20L;
    private static final ChatRoomCreateRequest REQUEST = new ChatRoomCreateRequest("01J3ARSNIPLISTING000001");

    @Test
    @DisplayName("첫 시도가 성공하면 그대로 반환하고 한 번만 호출한다")
    void open_succeedsOnFirstTry() {
        ChatRoomCreateResponse expected = new ChatRoomCreateResponse("01J3ARSNIPROOM0000000001", true, ListingStatus.ACTIVE);
        when(chatRoomCreator.open(BUYER_ID, REQUEST)).thenReturn(expected);

        ChatRoomCreateResponse response = chatRoomCommandService.open(BUYER_ID, REQUEST);

        assertThat(response).isEqualTo(expected);
        verify(chatRoomCreator, times(1)).open(BUYER_ID, REQUEST);
    }

    @Test
    @DisplayName("동시 클릭에서 진 첫 시도는 UNIQUE 위반을 던지고, 재시도가 상대가 만든 방을 반환한다")
    void open_retriesOnceAfterUniqueViolation() {
        ChatRoomCreateResponse existingRoom = new ChatRoomCreateResponse("01J3ARSNIPROOM0000000001", false, ListingStatus.ACTIVE);
        when(chatRoomCreator.open(BUYER_ID, REQUEST))
                .thenThrow(UniqueViolationFixtures.violationOf("chat_rooms", ChatRoom.UK_BUYER_LISTING))
                .thenReturn(existingRoom);

        ChatRoomCreateResponse response = chatRoomCommandService.open(BUYER_ID, REQUEST);

        assertThat(response).isEqualTo(existingRoom);
        assertThat(response.created()).isFalse();
        verify(chatRoomCreator, times(2)).open(BUYER_ID, REQUEST);
    }

    @Test
    @DisplayName("재시도도 실패하면 조용히 삼키지 않고 그대로 올린다 (루프를 돌리지 않는다)")
    void open_secondFailureIsNotSwallowed() {
        DataIntegrityViolationException second =
                UniqueViolationFixtures.violationOf("chat_rooms", ChatRoom.UK_BUYER_LISTING);
        when(chatRoomCreator.open(BUYER_ID, REQUEST))
                .thenThrow(UniqueViolationFixtures.violationOf("chat_rooms", ChatRoom.UK_BUYER_LISTING))
                .thenThrow(second);

        assertThatThrownBy(() -> chatRoomCommandService.open(BUYER_ID, REQUEST))
                .isSameAs(second);
        verify(chatRoomCreator, times(2)).open(BUYER_ID, REQUEST);
    }

    @Test
    @DisplayName("다른 제약(public_id) 위반은 재시도하지 않고 즉시 올린다 — 새 ULID로 우연히 성공해 원인이 가려지는 것을 막는다")
    void open_otherConstraintViolation_isNotRetried() {
        DataIntegrityViolationException publicIdViolation =
                UniqueViolationFixtures.violationOf("chat_rooms", ChatRoom.UK_PUBLIC_ID);
        when(chatRoomCreator.open(BUYER_ID, REQUEST)).thenThrow(publicIdViolation);

        assertThatThrownBy(() -> chatRoomCommandService.open(BUYER_ID, REQUEST))
                .isSameAs(publicIdViolation);
        verify(chatRoomCreator, times(1)).open(BUYER_ID, REQUEST);
    }

    @Test
    @DisplayName("제약 이름을 알 수 없는 무결성 위반도 재시도하지 않는다")
    void open_unknownIntegrityViolation_isNotRetried() {
        DataIntegrityViolationException unknown = new DataIntegrityViolationException("원인 불명");
        when(chatRoomCreator.open(BUYER_ID, REQUEST)).thenThrow(unknown);

        assertThatThrownBy(() -> chatRoomCommandService.open(BUYER_ID, REQUEST))
                .isSameAs(unknown);
        verify(chatRoomCreator, times(1)).open(BUYER_ID, REQUEST);
    }
}
