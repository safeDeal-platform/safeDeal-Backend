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
 * 채팅 메시지 한 건. 정책(노션 "채팅 — 재민" "메시지 보존")이 "텍스트만 · 삭제 없음"이라고
 * 못 박았다 — 그래서 상태·타입 enum도, 소프트삭제 필드도 없다.
 *
 * <p><b>{@code CreatedEntity}를 상속하는 이유(MutableEntity 아님):</b> 판정 기준은
 * "실제 UPDATE가 있는지"다({@code CreatedEntity} 클래스 주석). 이 테이블은 신고 후 숨김·
 * 마스킹이 생기면 그 판정이 뒤집히지만, 그 기능은 지금 범위에 없다 — 생기면 그때
 * {@code MutableEntity}로 옮긴다(V1 전환 전이라 지금은 그 비용이 0에 가깝다).
 *
 * <p><b>외부 식별자가 ULID가 아니라 내부 PK(숫자)인 이유:</b> API 명세서(CHT-2/3/5)가
 * {@code messageId}를 숫자로 못박았고(예시 {@code "messageId": 1024}), 읽음 커서·페이지
 * 커서 둘 다 이 값의 대소 비교로 동작한다. {@code Notification}도 이미 같은 방식이다 —
 * public_id 컬럼 없이 내부 id를 {@code notificationId}로 그대로 응답한다
 * ({@code NotificationItemResponse}). "DB 기초 규칙상 모든 테이블은 public_id를 갖는다"는
 * 문서 원칙과는 어긋나지만, 명세와 기존 선례를 따르기로 확정했다(설계 논의 결과).
 *
 * <p>{@code roomId}/{@code senderId}는 {@code ChatRoom}과 같은 방식으로 순수 컬럼
 * 비정규화다({@code ChatRoom} 클래스 주석 참고) — {@code @ManyToOne}을 쓰지 않는다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "chat_messages",
        uniqueConstraints = {
                // 재전송 중복 저장 방지(API 명세서 CHT-2 "멱등: clientMessageId + UNIQUE").
                @UniqueConstraint(name = "uk_chat_messages_room_client_msg",
                        columnNames = {"room_id", "client_message_id"})
        },
        indexes = {
                // CHT-5 목록 조회(WHERE room_id=? AND id<cursor ORDER BY id DESC)를 받친다.
                // UNIQUE의 선두도 room_id지만 두 번째 컬럼이 client_message_id라 id 범위
                // 스캔에는 못 쓴다 — Notification의 (user_id, id) 인덱스와 같은 이유.
                @Index(name = "idx_chat_messages_room_id", columnList = "room_id, id")
        }
)
public class ChatMessage extends CreatedEntity {

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

    /**
     * 클라이언트 발급 멱등 키. NULL을 허용하지 않는다 — MySQL UNIQUE는 NULL을 서로 다른
     * 값으로 취급해, nullable로 두면 클라이언트가 값을 안 보내는 순간 "중복 저장 DB 레벨
     * 차단"(정책)이 무력화된다.
     */
    @Column(name = "client_message_id", nullable = false, length = MAX_CLIENT_MESSAGE_ID_LENGTH,
            updatable = false)
    private String clientMessageId;

    private ChatMessage(Long roomId, Long senderId, String content, String clientMessageId) {
        this.roomId = roomId;
        this.senderId = senderId;
        this.content = content;
        this.clientMessageId = clientMessageId;
    }

    /**
     * 메시지를 만든다. STOMP 경로는 {@code @Valid}가 안 먹으므로(API 명세서 CHT-2), 이
     * 팩토리가 REST·STOMP·테스트 전부가 반드시 통과하는 유일한 검증 지점이다 — trim 후
     * 길이를 다시 잰다({@code @Size}만으로는 trim 전 길이를 재 이 요구를 못 만족한다).
     */
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
