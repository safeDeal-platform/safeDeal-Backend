package com.safedeal.domain.chat.repository;

import com.safedeal.domain.chat.entity.ChatMessage;
import com.safedeal.testsupport.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 신고 접수(POST /api/reports)의 증거 메시지 검증에 쓰는 조회. "이 방의, 이 사람이 보낸 메시지인가"를
 * 쿼리 하나로 판정한다 — 방 소속(RPT-C12)·보낸 사람(RPT-C13)·존재(RPT-C14)를 실제 MySQL에서 확인한다.
 */
@Transactional
class ChatMessageEvidenceLookupTest extends IntegrationTestSupport {

    private static final Long ROOM = 10L;
    private static final Long OTHER_ROOM = 11L;
    private static final Long REPORTER = 20L;
    private static final Long PARTNER = 30L;

    @Autowired
    ChatMessageRepository chatMessageRepository;

    private Long write(Long roomId, Long senderId, String clientMessageId) {
        return chatMessageRepository.saveAndFlush(
                ChatMessage.write(roomId, senderId, "메시지", clientMessageId)).getId();
    }

    @Test
    @DisplayName("RPT-C02 같은 방에서 상대가 보낸 메시지면 true")
    void partnerMessageInSameRoom() {
        Long id = write(ROOM, PARTNER, "c-1");

        assertThat(chatMessageRepository.existsByIdAndRoomIdAndSenderId(id, ROOM, PARTNER)).isTrue();
    }

    @Test
    @DisplayName("RPT-C12 다른 방의 메시지면 false")
    void messageInOtherRoom() {
        Long id = write(OTHER_ROOM, PARTNER, "c-1");

        assertThat(chatMessageRepository.existsByIdAndRoomIdAndSenderId(id, ROOM, PARTNER)).isFalse();
    }

    @Test
    @DisplayName("RPT-C13 신고자 본인이 보낸 메시지면 상대 기준 조회에서 false")
    void reporterOwnMessage() {
        Long id = write(ROOM, REPORTER, "c-1");

        assertThat(chatMessageRepository.existsByIdAndRoomIdAndSenderId(id, ROOM, PARTNER)).isFalse();
    }

    @Test
    @DisplayName("RPT-C14 없는 메시지 id면 false")
    void missingMessage() {
        assertThat(chatMessageRepository.existsByIdAndRoomIdAndSenderId(999_999L, ROOM, PARTNER)).isFalse();
    }
}
