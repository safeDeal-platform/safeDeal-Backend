package com.safedeal.domain.chat.dto;

import com.safedeal.domain.chat.entity.ChatMessage;

import java.time.Instant;

/**
 * 메시지 저장 결과. 필드 모양을 API 명세서(CHT-2)의 브로드캐스트 payload와 맞춰뒀다 —
 * 다음 슬라이스(STOMP)가 이 값을 그대로 직렬화할 수 있게 하기 위해서다. {@code senderId}를
 * 공개 식별자(ULID)로 바꾸는 변환은 여기서 하지 않는다 — 방의 참여자는 2명뿐이라 그 변환은
 * 발행/응답을 조립하는 한 곳(다음 슬라이스)에서 하면 충분하다.
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
