package com.safedeal.domain.chat.service;

import com.safedeal.domain.chat.dto.ChatReadResponse;
import com.safedeal.domain.chat.entity.ChatRoom;
import com.safedeal.domain.chat.repository.ChatMessageRepository;
import com.safedeal.domain.chat.repository.ChatRoomRepository;
import com.safedeal.global.exception.BusinessException;
import com.safedeal.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 채팅 읽음 처리(API 명세서 CHT-3). ① 방 조회(참여자 조건 내장 — 아니면 존재 자체를
 * 숨기고 404) ② messageId가 이 방 소속인지 확인(아니면 400) ③ 조건부 UPDATE로 커서
 * 전진(역행이면 0행, 그래도 실패 아님 — {@link ChatRoomRepository#markBuyerRead} 참고)
 * ④ 0행이면 {@link ChatRoomRepository#findForReadCursorRecheck}로 실제 커서를 다시 읽어
 * 응답한다(CONC-2 — 평범한 재조회는 REPEATABLE READ 스냅샷에 걸려 동시 커밋을 못 본다).
 */
@Service
@RequiredArgsConstructor
public class ChatReadCommandService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatMessageRepository chatMessageRepository;

    @Transactional
    public ChatReadResponse markAsRead(Long userId, String roomPublicId, Long lastReadMessageId) {
        ChatRoom room = chatRoomRepository
                .findByPublicIdAndParticipant(roomPublicId, userId)
                .orElseThrow(() -> new BusinessException(
                        CommonErrorCode.RESOURCE_NOT_FOUND, "채팅방을 찾을 수 없습니다."));

        if (!chatMessageRepository.existsByIdAndRoomId(lastReadMessageId, room.getId())) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, "존재하지 않는 메시지입니다.");
        }

        boolean isBuyer = room.isBuyer(userId);
        Instant now = Instant.now();
        int updated = isBuyer
                ? chatRoomRepository.markBuyerRead(room.getId(), lastReadMessageId, now)
                : chatRoomRepository.markSellerRead(room.getId(), lastReadMessageId, now);

        // 0행이면 이미 그 값 이상으로 읽은 상태라는 뜻이다 — 내가 보낸 값이 역행이었을
        // 수도 있고, 그 사이 다른 기기의 요청이 먼저 더 큰 값으로 전진시켰을 수도 있다.
        // 어느 쪽이든 방 조회 시점(1단계)에 들고 있던 커서는 이미 낡았을 수 있어 그대로
        // 쓰면 실제보다 뒤처진 값을 응답할 위험이 있다 — 다시 읽어 진짜 값을 응답한다.
        //
        // 평범한 findById가 아니라 findForReadCursorRecheck(공유 잠금 읽기)를 쓴다
        // (CONC-2) — 이 메서드도 같은 트랜잭션 안이라, 평범한 재조회는 REPEATABLE READ
        // 스냅샷에 걸려 "그 사이 다른 트랜잭션이 커밋한 값"을 못 본다. 잠금 읽기만
        // 지금 실제로 커밋된 값을 본다(근거: ChatRoomRepository#findForReadCursorRecheck
        // Javadoc, ChatReadCommandServiceIntegrationTest).
        long responseCursor;
        if (updated > 0) {
            responseCursor = lastReadMessageId;
        } else {
            ChatRoom reloaded = chatRoomRepository.findForReadCursorRecheck(room.getId()).orElseThrow();
            responseCursor = isBuyer
                    ? reloaded.getBuyerLastReadMessageId()
                    : reloaded.getSellerLastReadMessageId();
        }
        return new ChatReadResponse(responseCursor);
    }
}
