package com.safedeal.domain.chat.dto;

/**
 * 메시지 저장 서비스의 내부 입력 계약(HTTP/STOMP 계약 아님).
 *
 * @param roomId          방의 공개 식별자(ULID)
 * @param content         trim 전 원본 내용. trim·길이 검증은 {@code ChatMessage.write}가 한다
 * @param clientMessageId 클라이언트 발급 멱등 키
 */
public record ChatMessageAppendCommand(String roomId, String content, String clientMessageId) {
}
