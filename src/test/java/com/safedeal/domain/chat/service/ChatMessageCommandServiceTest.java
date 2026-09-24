package com.safedeal.domain.chat.service;

import com.safedeal.domain.chat.dto.ChatMessageAppendCommand;
import com.safedeal.domain.chat.dto.ChatMessageAppendResult;
import com.safedeal.domain.chat.entity.ChatMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 재전송 재시도 구조만 검증한다. 실제 저장 로직은 {@link ChatMessageAppenderTest}가 본다.
 */
@ExtendWith(MockitoExtension.class)
class ChatMessageCommandServiceTest {

    @Mock
    ChatMessageAppender chatMessageAppender;

    @InjectMocks
    ChatMessageCommandService chatMessageCommandService;

    private static final Long SENDER_ID = 20L;
    private static final ChatMessageAppendCommand COMMAND =
            new ChatMessageAppendCommand("01J3ARSNIPROOM0000000001", "안녕하세요", "c-uuid-1");

    @Test
    @DisplayName("첫 시도가 성공하면 한 번만 호출한다")
    void append_succeedsOnFirstTry() {
        ChatMessageAppendResult expected = new ChatMessageAppendResult(
                100L, "01J3ARSNIPROOM0000000001", SENDER_ID, "안녕하세요", "c-uuid-1", Instant.now());
        when(chatMessageAppender.append(SENDER_ID, COMMAND)).thenReturn(expected);

        ChatMessageAppendResult result = chatMessageCommandService.append(SENDER_ID, COMMAND);

        assertThat(result).isEqualTo(expected);
        verify(chatMessageAppender, times(1)).append(SENDER_ID, COMMAND);
    }

    @Test
    @DisplayName("재전송이 겹쳐 UNIQUE 위반이 나면 1회 재시도해 승자의 메시지를 반환한다")
    void append_retriesOnceAfterUniqueViolation() {
        ChatMessageAppendResult existing = new ChatMessageAppendResult(
                100L, "01J3ARSNIPROOM0000000001", SENDER_ID, "안녕하세요", "c-uuid-1", Instant.now());
        when(chatMessageAppender.append(SENDER_ID, COMMAND))
                .thenThrow(UniqueViolationFixtures.violationOf("chat_messages", ChatMessage.UK_ROOM_CLIENT_MSG))
                .thenReturn(existing);

        ChatMessageAppendResult result = chatMessageCommandService.append(SENDER_ID, COMMAND);

        assertThat(result).isEqualTo(existing);
        verify(chatMessageAppender, times(2)).append(SENDER_ID, COMMAND);
    }

    @Test
    @DisplayName("재시도도 실패하면 조용히 삼키지 않고 그대로 올린다")
    void append_secondFailureIsNotSwallowed() {
        DataIntegrityViolationException second =
                UniqueViolationFixtures.violationOf("chat_messages", ChatMessage.UK_ROOM_CLIENT_MSG);
        when(chatMessageAppender.append(SENDER_ID, COMMAND))
                .thenThrow(UniqueViolationFixtures.violationOf("chat_messages", ChatMessage.UK_ROOM_CLIENT_MSG))
                .thenThrow(second);

        assertThatThrownBy(() -> chatMessageCommandService.append(SENDER_ID, COMMAND))
                .isSameAs(second);
        verify(chatMessageAppender, times(2)).append(SENDER_ID, COMMAND);
    }

    @Test
    @DisplayName("다른 제약 위반은 재시도하지 않고 즉시 올린다")
    void append_otherConstraintViolation_isNotRetried() {
        DataIntegrityViolationException other =
                UniqueViolationFixtures.violationOf("chat_messages", "PRIMARY");
        when(chatMessageAppender.append(SENDER_ID, COMMAND)).thenThrow(other);

        assertThatThrownBy(() -> chatMessageCommandService.append(SENDER_ID, COMMAND))
                .isSameAs(other);
        verify(chatMessageAppender, times(1)).append(SENDER_ID, COMMAND);
    }

    @Test
    @DisplayName("제약 이름을 알 수 없는 무결성 위반도 재시도하지 않는다")
    void append_unknownIntegrityViolation_isNotRetried() {
        DataIntegrityViolationException unknown = new DataIntegrityViolationException("원인 불명");
        when(chatMessageAppender.append(SENDER_ID, COMMAND)).thenThrow(unknown);

        assertThatThrownBy(() -> chatMessageCommandService.append(SENDER_ID, COMMAND))
                .isSameAs(unknown);
        verify(chatMessageAppender, times(1)).append(SENDER_ID, COMMAND);
    }
}
