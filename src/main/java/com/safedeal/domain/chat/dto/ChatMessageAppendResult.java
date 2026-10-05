package com.safedeal.domain.chat.dto;

import com.safedeal.domain.chat.entity.ChatMessage;

import java.time.Instant;

/**
 * 메시지 저장 결과. 필드 모양을 API 명세서(CHT-2) 브로드캐스트 payload와 맞춰 다음
 * 슬라이스(STOMP)가 그대로 직렬화할 수 있게 한다. {@code senderId}를 공개 식별자로
 * 바꾸는 변환은 응답을 조립하는 곳에서 한 번만 하면 되므로 여기서는 하지 않는다.
 */
public record ChatMessageAppendResult(
        Long messageId,
        String roomId,
        Long senderId,
        String content,
        String clientMessageId,
        Instant createdAt
) {

    public static ChatMessageAppendResult of(ChatMessage message, String roomPublicId) {
        return new ChatMessageAppendResult(
                message.getId(), roomPublicId, message.getSenderId(),
                message.getContent(), message.getClientMessageId(), message.getCreatedAt());
    }
}
