package com.safedeal.domain.chat.repository;

import com.safedeal.domain.chat.entity.ChatMessage;
import com.safedeal.testsupport.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 파생 쿼리와 UNIQUE(room_id, client_message_id)가 실제 MySQL에서 도는지 확인한다.
 */
@Transactional
class ChatMessageRepositoryTest extends IntegrationTestSupport {

    @Autowired
    ChatMessageRepository chatMessageRepository;

    @Test
    @DisplayName("room_id, client_message_id로 조회된다")
    void findsByRoomAndClientMessageId() {
        ChatMessage saved = chatMessageRepository.saveAndFlush(
                ChatMessage.write(10L, 20L, "안녕하세요", "c-uuid-1"));

        Optional<ChatMessage> found = chatMessageRepository
                .findByRoomIdAndClientMessageId(10L, "c-uuid-1");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
    }

    @Test
    @DisplayName("다른 방의 같은 clientMessageId는 충돌하지 않는다")
    void sameClientMessageId_differentRoom_doesNotConflict() {
        chatMessageRepository.saveAndFlush(ChatMessage.write(10L, 20L, "안녕", "c-uuid-1"));

        ChatMessage saved = chatMessageRepository.saveAndFlush(
                ChatMessage.write(11L, 20L, "다른 방", "c-uuid-1"));

        assertThat(chatMessageRepository.findByRoomIdAndClientMessageId(11L, "c-uuid-1"))
                .get().extracting(ChatMessage::getId).isEqualTo(saved.getId());
    }

    @Test
    @DisplayName("같은 방에 같은 clientMessageId를 두 번 저장하면 UNIQUE 위반이 난다 (재전송 중복 방지)")
    void sameRoomAndClientMessageId_violatesUniqueConstraint() {
        chatMessageRepository.saveAndFlush(ChatMessage.write(10L, 20L, "안녕", "c-uuid-1"));

        assertThatThrownBy(() -> chatMessageRepository.saveAndFlush(
                ChatMessage.write(10L, 20L, "또 안녕", "c-uuid-1")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("없는 조합은 빈 값이다")
    void findsNothing_whenNoMatch() {
        assertThat(chatMessageRepository.findByRoomIdAndClientMessageId(999L, "no-such-id"))
                .isEmpty();
    }
}
