package com.safedeal.domain.chat.entity;

import com.safedeal.global.entity.CreatedEntity;
import com.safedeal.global.exception.BusinessException;
import com.safedeal.global.exception.CommonErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 채팅 메시지 한 건. 텍스트만 저장하고 삭제하지 않는다(정책) — 그래서 상태·삭제 필드가 없다.
 *
 * <p>외부에 그대로 내보내는 id가 ULID가 아니라 숫자 PK다. 읽음 커서·페이지 커서가 전부 이
 * 숫자의 크기 비교로 동작하기 때문이다 — 나중에 다른 식별자로 바꾸면 안 된다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "chat_messages",
        uniqueConstraints = {
                // 재전송 중복 저장 방지(API 명세서 CHT-2 "멱등: clientMessageId + UNIQUE").
                @UniqueConstraint(name = ChatMessage.UK_ROOM_CLIENT_MSG,
                        columnNames = {"room_id", "client_message_id"})
        },
        indexes = {
                // 메시지 목록 조회(WHERE room_id=? AND id<cursor ORDER BY id DESC)를 받치는 인덱스.
                @Index(name = "idx_chat_messages_room_id", columnList = "room_id, id")
        }
)
public class ChatMessage extends CreatedEntity {

    /** 재시도 판정({@code ChatMessageCommandService})과 애너테이션이 같은 이름을 쓰게 하는 상수. */
    public static final String UK_ROOM_CLIENT_MSG = "uk_chat_messages_room_client_msg";

    /** 정책 상한(trim 후 1~1,000자). */
    public static final int MAX_CONTENT_LENGTH = 1_000;

    /** clientMessageId 최대 길이. UUID 문자열(36자) 기준에 여유를 둔 가정값. */
    public static final int MAX_CLIENT_MESSAGE_ID_LENGTH = 64;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_id", nullable = false, updatable = false)
    private Long roomId;

    @Column(name = "sender_id", nullable = false, updatable = false)
    private Long senderId;

    @Column(nullable = false, length = MAX_CONTENT_LENGTH, updatable = false)
    private String content;

    /** 클라이언트가 재전송을 구분하는 키. NULL을 허용하면 MySQL이 NULL끼리는 서로 다른 값으로 봐서 중복 차단이 풀린다. */
    @Column(name = "client_message_id", nullable = false, length = MAX_CLIENT_MESSAGE_ID_LENGTH,
            updatable = false)
    private String clientMessageId;

    private ChatMessage(Long roomId, Long senderId, String content, String clientMessageId) {
        this.roomId = roomId;
        this.senderId = senderId;
        this.content = content;
        this.clientMessageId = clientMessageId;
    }

    /** 메시지를 만든다. STOMP 경로는 요청 검증(@Valid)이 안 통하므로, 이 메서드가 모든 경로가 거치는 유일한 검증 지점이다. */
    public static ChatMessage write(Long roomId, Long senderId, String content, String clientMessageId) {
        if (roomId == null || senderId == null) {
            throw new IllegalArgumentException("roomId와 senderId는 필수입니다");
        }
        if (clientMessageId == null || clientMessageId.isBlank()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, "clientMessageId는 필수입니다.");
        }
        if (clientMessageId.length() > MAX_CLIENT_MESSAGE_ID_LENGTH) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT,
                    "clientMessageId는 %d자 이하여야 합니다.".formatted(MAX_CLIENT_MESSAGE_ID_LENGTH));
        }
        String trimmed = content == null ? "" : content.strip();
        if (trimmed.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, "메시지 내용은 비어 있을 수 없습니다.");
        }
        if (trimmed.length() > MAX_CONTENT_LENGTH) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT,
                    "메시지는 %,d자 이하여야 합니다.".formatted(MAX_CONTENT_LENGTH));
        }
        return new ChatMessage(roomId, senderId, trimmed, clientMessageId);
    }
}
