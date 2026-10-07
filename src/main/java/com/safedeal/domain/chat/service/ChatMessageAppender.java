package com.safedeal.domain.chat.service;

import com.safedeal.domain.chat.dto.ChatMessageAppendCommand;
import com.safedeal.domain.chat.dto.ChatMessageAppendResult;
import com.safedeal.domain.chat.entity.ChatMessage;
import com.safedeal.domain.chat.entity.ChatRoom;
import com.safedeal.domain.chat.repository.ChatMessageRepository;
import com.safedeal.domain.chat.repository.ChatRoomRepository;
import com.safedeal.global.exception.BusinessException;
import com.safedeal.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** 메시지 저장의 단일 시도(트랜잭션 경계). {@link ChatMessageCommandService}가 재전송 충돌 시 이 메서드를 재시도한다. */
@Service
@RequiredArgsConstructor
public class ChatMessageAppender {

    private final ChatMessageRepository chatMessageRepository;
    private final ChatRoomRepository chatRoomRepository;

    /**
     * 방 조회(참여자 아니면 존재를 숨기고 404) → 재전송이면 그대로 반환 → 저장 → 발신자
     * 본인 커서 갱신(안 하면 내가 보낸 메시지가 내 안읽음으로 잡힌다).
     */
    @Transactional
    public ChatMessageAppendResult append(Long senderId, ChatMessageAppendCommand command) {
        ChatRoom room = chatRoomRepository
                .findByPublicIdAndParticipant(command.roomId(), senderId)
                .orElseThrow(() -> new BusinessException(
                        CommonErrorCode.RESOURCE_NOT_FOUND, "채팅방을 찾을 수 없습니다."));

        // 재전송이면 커서를 다시 건드리지 않는다 — 저장과 커서 갱신이 한 트랜잭션이라, 이
        // 조회로 기존 행이 보인다는 건 그 트랜잭션의 커서 갱신도 이미 끝났다는 뜻이다.
        ChatMessage existing = chatMessageRepository
                .findByRoomIdAndClientMessageId(room.getId(), command.clientMessageId())
                .orElse(null);
        if (existing != null) {
            return ChatMessageAppendResult.of(existing, room.getPublicId());
        }

        ChatMessage saved = chatMessageRepository.save(
                ChatMessage.write(room.getId(), senderId, command.content(), command.clientMessageId()));

        advanceSenderCursor(room, senderId, saved.getId());

        return ChatMessageAppendResult.of(saved, room.getPublicId());
    }

    private void advanceSenderCursor(ChatRoom room, Long senderId, Long messageId) {
        Instant now = Instant.now();
        if (room.isBuyer(senderId)) {
            chatRoomRepository.markBuyerRead(room.getId(), messageId, now);
        } else {
            chatRoomRepository.markSellerRead(room.getId(), messageId, now);
        }
    }
}
