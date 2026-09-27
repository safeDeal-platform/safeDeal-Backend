package com.safedeal.domain.chat.entity;

import com.safedeal.global.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatMessageTest {

    @Test
    @DisplayName("앞뒤 공백은 제거하고 저장한다")
    void write_stripsSurroundingWhitespace() {
        ChatMessage message = ChatMessage.write(10L, 20L, "  안녕하세요  ", "c-uuid-1");

        assertThat(message.getContent()).isEqualTo("안녕하세요");
        assertThat(message.getRoomId()).isEqualTo(10L);
        assertThat(message.getSenderId()).isEqualTo(20L);
        assertThat(message.getClientMessageId()).isEqualTo("c-uuid-1");
    }

    @Test
    @DisplayName("공백만 입력하면 trim 후 빈 문자열이라 400 C001")
    void write_blankAfterTrim_throwsInvalidInput() {
        assertThatThrownBy(() -> ChatMessage.write(10L, 20L, "   ", "c-uuid-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("비어 있을 수 없습니다");
    }

    @Test
    @DisplayName("trim 후 1,000자는 통과한다 (경계값)")
    void write_exactlyMaxLength_succeeds() {
        String content = "가".repeat(ChatMessage.MAX_CONTENT_LENGTH);

        ChatMessage message = ChatMessage.write(10L, 20L, content, "c-uuid-1");

        assertThat(message.getContent()).hasSize(ChatMessage.MAX_CONTENT_LENGTH);
    }

    @Test
    @DisplayName("trim 후 1,001자는 400 C001 (경계값)")
    void write_exceedsMaxLength_throwsInvalidInput() {
        String content = "가".repeat(ChatMessage.MAX_CONTENT_LENGTH + 1);

        assertThatThrownBy(() -> ChatMessage.write(10L, 20L, content, "c-uuid-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("1,000자 이하");
    }

    @Test
    @DisplayName("trim 전 길이가 상한을 넘어도 trim 후 통과하면 저장된다")
    void write_trimBringsUnderLimit_succeeds() {
        String content = "  " + "가".repeat(ChatMessage.MAX_CONTENT_LENGTH) + "  ";

        ChatMessage message = ChatMessage.write(10L, 20L, content, "c-uuid-1");

        assertThat(message.getContent()).hasSize(ChatMessage.MAX_CONTENT_LENGTH);
    }

    @Test
    @DisplayName("clientMessageId가 비어 있으면 400 C001")
    void write_blankClientMessageId_throwsInvalidInput() {
        assertThatThrownBy(() -> ChatMessage.write(10L, 20L, "안녕", ""))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("clientMessageId는 필수");
    }

    @Test
    @DisplayName("clientMessageId가 64자를 넘으면 400 C001")
    void write_clientMessageIdTooLong_throwsInvalidInput() {
        String tooLong = "c".repeat(ChatMessage.MAX_CLIENT_MESSAGE_ID_LENGTH + 1);

        assertThatThrownBy(() -> ChatMessage.write(10L, 20L, "안녕", tooLong))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("64자 이하");
    }
}
