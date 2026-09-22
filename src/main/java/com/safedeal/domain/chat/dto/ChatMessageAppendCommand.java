package com.safedeal.domain.chat.dto;

/**
 * 메시지 저장 서비스의 내부 입력 계약. HTTP/STOMP 계약이 아니다 — 오늘 슬라이스는 REST/STOMP
 * 진입점이 없고, 다음 슬라이스(STOMP)가 이 계약을 그대로 호출한다.
 *
 * @param roomId          방의 공개 식별자(ULID, {@code ChatRoom.publicId})
 * @param content         trim 전 원본 내용. trim·길이 검증은 {@code ChatMessage.write}가 한다
 * @param clientMessageId 클라이언트 발급 멱등 키
 */
public record ChatMessageAppendCommand(String roomId, String content, String clientMessageId) {
}
