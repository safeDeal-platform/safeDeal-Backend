package com.safedeal.domain.chat.service;

import com.safedeal.domain.chat.dto.ChatMessageAppendCommand;
import com.safedeal.domain.chat.dto.ChatMessageAppendResult;
import com.safedeal.domain.chat.entity.ChatRoom;
import com.safedeal.domain.chat.repository.ChatMessageRepository;
import com.safedeal.domain.chat.repository.ChatRoomRepository;
import com.safedeal.testsupport.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API 명세서 CHT-2 "멱등: clientMessageId + UNIQUE"를 재현한다. 같은 요청이 네트워크
 * 재전송으로 동시에 두 번 도착해도 메시지는 1건만 저장돼야 한다.
 *
 * <p>{@code @Transactional}을 붙이지 않는다 — 별도 스레드는 테스트 트랜잭션 밖이라 롤백이
 * 안 통한다({@link IntegrationTestSupport} 클래스 주석). {@link #cleanUp()}에서 직접 정리하고,
 * 고정 sleep 대신 {@link CountDownLatch}로 동시 출발시킨다.
 */
class ChatMessageConcurrencyTest extends IntegrationTestSupport {

    @Autowired
    ChatMessageCommandService chatMessageCommandService;

    @Autowired
    ChatMessageRepository chatMessageRepository;

    @Autowired
    ChatRoomRepository chatRoomRepository;

    private ChatRoom room;

    @BeforeEach
    void setUp() {
        room = chatRoomRepository.saveAndFlush(
                ChatRoom.open("01J3ARSNIPCONCURRENCY002", 99L, 20L, 30L));
    }

    @AfterEach
    void cleanUp() {
        chatMessageRepository.deleteAllInBatch();
        chatRoomRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("같은 clientMessageId로 동시 2요청 → 메시지는 1건만 저장되고, 둘 다 같은 messageId를 받는다")
    void concurrentResend_savesExactlyOneMessage() throws InterruptedException {
        ChatMessageAppendCommand command =
                new ChatMessageAppendCommand(room.getPublicId(), "안녕하세요", "c-uuid-concurrent");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<ChatMessageAppendResult> responses;
        try {
            List<Future<ChatMessageAppendResult>> futures = List.of(
                    executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return chatMessageCommandService.append(20L, command);
                    }),
                    executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return chatMessageCommandService.append(20L, command);
                    })
            );

            ready.await();
            start.countDown();

            responses = futures.stream()
                    .map(f -> {
                        try {
                            return f.get(10, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .toList();
        } finally {
            // 결과 수집이 실패해도 비데몬 스레드가 남아 테스트 프로세스가 안 끝나는 일이 없도록.
            executor.shutdownNow();
        }

        assertThat(responses.get(0).messageId()).isEqualTo(responses.get(1).messageId());
        assertThat(chatMessageRepository.findByRoomIdAndClientMessageId(room.getId(), "c-uuid-concurrent"))
                .isPresent();
        assertThat(chatMessageRepository.findAll()).hasSize(1);
    }
}
