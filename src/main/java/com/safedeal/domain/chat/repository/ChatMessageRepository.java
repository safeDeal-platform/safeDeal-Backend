package com.safedeal.domain.chat.repository;

import com.safedeal.domain.chat.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 조건이 고정된 정적 쿼리라 QueryDSL 없이 파생 쿼리로 충분하다(알림·채팅방 리포지토리와
 * 같은 전략).
 */
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /** 재전송 멱등 조회. 조건 순서를 UNIQUE 컬럼 순서(room_id, client_message_id)와 맞춘다. */
    Optional<ChatMessage> findByRoomIdAndClientMessageId(Long roomId, String clientMessageId);
}
