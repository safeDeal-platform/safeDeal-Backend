package com.safedeal.domain.chat.service;

import com.safedeal.domain.chat.dto.ChatReadResponse;
import com.safedeal.domain.chat.entity.ChatMessage;
import com.safedeal.domain.chat.entity.ChatRoom;
import com.safedeal.domain.chat.repository.ChatMessageRepository;
import com.safedeal.domain.chat.repository.ChatRoomRepository;
import com.safedeal.testsupport.IntegrationTestSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CONC-2 회귀 고정: MySQL REPEATABLE READ(한 트랜잭션 안에서는 그 트랜잭션이 처음 읽은 시점의
 * "사진"만 계속 보이고, 그 사이 다른 트랜잭션이 커밋해도 평범한 SELECT로는 안 보인다)에서
 * {@link ChatReadCommandService#markAsRead} 의 0행-재조회 분기가 낡은 사진이 아니라 지금
 * DB에 실제로 커밋된 값을 돌려주는지 실제 MySQL로 고정한다.
 *
 * <p>트랜잭션 하나로는 "다른 트랜잭션이 그 사이 커밋했다"를 만들 수 없어 {@code @Transactional}을
 * 붙이지 않고 {@code REQUIRES_NEW}로 직접 겹친다({@link IntegrationTestSupport} 클래스 주석,
 * {@code ChatRoomRejoinIntegrationTest}와 같은 패턴). 정리는 {@link #cleanUp()}에서 한다
 * ({@code ChatMessageConcurrencyTest}와 같은 순서 — 메시지 먼저, 방 나중).
 */
class ChatReadCommandServiceIntegrationTest extends IntegrationTestSupport {

    @Autowired
    ChatReadCommandService chatReadCommandService;

    @Autowired
    ChatRoomRepository chatRoomRepository;

    @Autowired
    ChatMessageRepository chatMessageRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    EntityManager entityManager;

    private static final String ROOM_PUBLIC_ID = "01J3ARSNIPREADCONC00000001";
    private static final Long BUYER_ID = 20L;
    private static final Long SELLER_ID = 30L;

    private Long roomId;
    private Long m1;
    private Long m2;
    private Long m3;
    private TransactionTemplate outer;
    private TransactionTemplate inner;

    @BeforeEach
    void setUp() {
        ChatRoom room = chatRoomRepository.saveAndFlush(
                ChatRoom.open(ROOM_PUBLIC_ID, 99L, BUYER_ID, SELLER_ID));
        roomId = room.getId();

        m1 = chatMessageRepository.saveAndFlush(
                ChatMessage.write(roomId, BUYER_ID, "메시지1", "c-conc-1")).getId();
        m2 = chatMessageRepository.saveAndFlush(
                ChatMessage.write(roomId, BUYER_ID, "메시지2", "c-conc-2")).getId();
        m3 = chatMessageRepository.saveAndFlush(
                ChatMessage.write(roomId, BUYER_ID, "메시지3", "c-conc-3")).getId();

        outer = new TransactionTemplate(transactionManager);
        inner = new TransactionTemplate(transactionManager);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @AfterEach
    void cleanUp() {
        chatMessageRepository.deleteAllInBatch();
        chatRoomRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("구매자: 조회 시점 스냅샷은 m1인데 그 사이 다른 트랜잭션이 m3로 커밋해도, "
            + "0행 재조회는 스냅샷(m1)이 아니라 실제 최신 커밋값(m3)을 응답한다")
    void markAsRead_buyer_concurrentCommitBetweenSnapshotAndRecheck_returnsLatestCommitted() {
        // @Modifying 쿼리는 활성 트랜잭션이 없으면 그 자체로 TransactionRequiredException이다
        // — 이 커밋은 테스트 준비 단계이므로 별도 트랜잭션으로 감싼다(검증 대상 시나리오와 무관).
        outer.executeWithoutResult(s -> chatRoomRepository.markBuyerRead(roomId, m1, Instant.now()));

        ChatReadResponse[] captured = new ChatReadResponse[1];
        outer.executeWithoutResult(status -> {
            // (a) 이 트랜잭션의 첫 읽기 — REPEATABLE READ 스냅샷이 여기서 고정된다.
            ChatRoom snapshot = chatRoomRepository.findById(roomId).orElseThrow();
            assertThat(snapshot.getBuyerLastReadMessageId()).isEqualTo(m1);

            // (b) 다른 트랜잭션이 커서를 m3로 올리고 커밋한다. outer는 아직 UPDATE를 하지 않아
            // 잠금 경합 없이 바로 끝난다 — 순서가 바뀌면(= outer가 먼저 UPDATE) inner가 그
            // 잠금에 걸려 innodb_lock_wait_timeout까지 블로킹된다.
            inner.executeWithoutResult(s -> chatRoomRepository.markBuyerRead(roomId, m3, Instant.now()));

            // (c) 이 트랜잭션에서 평범한 조회는 여전히 m1이어야 한다 — REPEATABLE READ 전제
            // 자체가 깨지지 않았는지 확인하는 자기 점검(공허한 단언 방지).
            Number stillSnapshot = (Number) entityManager
                    .createNativeQuery("SELECT buyer_last_read_message_id FROM chat_rooms WHERE id = :id")
                    .setParameter("id", roomId)
                    .getSingleResult();
            assertThat(stillSnapshot.longValue()).isEqualTo(m1);

            // (d) 역행 요청(m2 < 실제 최신값 m3) → 조건부 UPDATE 0행 → 재조회 분기.
            captured[0] = chatReadCommandService.markAsRead(BUYER_ID, ROOM_PUBLIC_ID, m2);
        });

        assertThat(captured[0].lastReadMessageId())
                .as("낡은 스냅샷(m1)도 요청값(m2)도 아니라 실제 최신 커밋값(m3)이어야 한다")
                .isEqualTo(m3);
        assertThat(chatRoomRepository.findById(roomId).orElseThrow().getBuyerLastReadMessageId())
                .isEqualTo(m3);
    }

    @Test
    @DisplayName("판매자: 같은 시나리오를 판매자 커서로 반복해도 실제 최신 커밋값을 응답한다")
    void markAsRead_seller_concurrentCommitBetweenSnapshotAndRecheck_returnsLatestCommitted() {
        outer.executeWithoutResult(s -> chatRoomRepository.markSellerRead(roomId, m1, Instant.now()));

        ChatReadResponse[] captured = new ChatReadResponse[1];
        outer.executeWithoutResult(status -> {
            ChatRoom snapshot = chatRoomRepository.findById(roomId).orElseThrow();
            assertThat(snapshot.getSellerLastReadMessageId()).isEqualTo(m1);

            inner.executeWithoutResult(s -> chatRoomRepository.markSellerRead(roomId, m3, Instant.now()));

            Number stillSnapshot = (Number) entityManager
                    .createNativeQuery("SELECT seller_last_read_message_id FROM chat_rooms WHERE id = :id")
                    .setParameter("id", roomId)
                    .getSingleResult();
            assertThat(stillSnapshot.longValue()).isEqualTo(m1);

            captured[0] = chatReadCommandService.markAsRead(SELLER_ID, ROOM_PUBLIC_ID, m2);
        });

        assertThat(captured[0].lastReadMessageId()).isEqualTo(m3);
        assertThat(chatRoomRepository.findById(roomId).orElseThrow().getSellerLastReadMessageId())
                .isEqualTo(m3);
    }

    @Test
    @DisplayName("동시성 없는 단순 역행 요청: 이미 m3로 커밋된 상태에서 m2를 요청하면 "
            + "(별도 트랜잭션이라 스냅샷 문제 자체가 없다) 실제 커서 m3를 응답한다")
    void markAsRead_buyer_nonConcurrentRegressiveRequest_returnsActualCursor() {
        outer.executeWithoutResult(s -> chatRoomRepository.markBuyerRead(roomId, m3, Instant.now()));
        assertThat(chatRoomRepository.findById(roomId).orElseThrow().getBuyerLastReadMessageId())
                .isEqualTo(m3);

        ChatReadResponse result = chatReadCommandService.markAsRead(BUYER_ID, ROOM_PUBLIC_ID, m2);

        assertThat(result.lastReadMessageId()).isEqualTo(m3);
    }
}
