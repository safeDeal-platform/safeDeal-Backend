package com.safedeal.domain.chat.repository;

import com.safedeal.domain.chat.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 조건이 고정된 정적 쿼리라 QueryDSL 없이 파생 쿼리로 충분하다. */
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /** 재전송 멱등 조회. 조건 순서를 UNIQUE 컬럼 순서(room_id, client_message_id)와 맞춘다. */
    Optional<ChatMessage> findByRoomIdAndClientMessageId(Long roomId, String clientMessageId);

    /**
     * 이 messageId가 이 방 소속인지(CHT-3 읽음 처리). room_id는 순수 컬럼이라 FK가
     * messageId 소속을 대신 검증해주지 않는다 — 읽음 커서 UPDATE 전에 이 조회로 걸러야
     * "다른 방 메시지"를 400으로 잡을 수 있다.
     */
    boolean existsByIdAndRoomId(Long id, Long roomId);
}
