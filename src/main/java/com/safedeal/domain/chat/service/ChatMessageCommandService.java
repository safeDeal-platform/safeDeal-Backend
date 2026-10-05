package com.safedeal.domain.chat.service;

import com.safedeal.domain.chat.dto.ChatMessageAppendCommand;
import com.safedeal.domain.chat.dto.ChatMessageAppendResult;
import com.safedeal.domain.chat.entity.ChatMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 다음 슬라이스(STOMP)의 진입점이 될 서비스. {@link ChatRoomCommandService}와 같은 이유로
 * 일부러 {@code @Transactional}을 안 붙인다.
 *
 * <p>여기서 겹치는 건 같은 요청의 네트워크 재전송이다 — 클라이언트가 응답을 못 받고 다시
 * 보낸 경우다. {@code uk_chat_messages_room_client_msg} 위반일 때만 한 번 재시도하고,
 * 그 외 위반은 원인이 다르므로 그대로 올린다.
 */
@Service
@RequiredArgsConstructor
public class ChatMessageCommandService {

    private final ChatMessageAppender chatMessageAppender;

    public ChatMessageAppendResult append(Long senderId, ChatMessageAppendCommand command) {
        try {
            return chatMessageAppender.append(senderId, command);
        } catch (DataIntegrityViolationException e) {
            if (!UniqueViolations.causedBy(e, ChatMessage.UK_ROOM_CLIENT_MSG)) {
                throw e;
            }
            return chatMessageAppender.append(senderId, command);
        }
    }
}
