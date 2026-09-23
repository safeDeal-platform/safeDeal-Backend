package com.safedeal.domain.chat.repository;

import com.safedeal.domain.chat.entity.ChatRoom;
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
 * 재진입 복구가 <b>다른 트랜잭션이 올려둔 읽음 커서를 되돌리지 않는지</b> 실제 MySQL로 고정한다
 * (lost update 회귀 테스트).
 *
 * <p>엔티티를 읽어 setter로 바꾸는 더티체킹 방식이었다면, Hibernate가 커밋 때 그 행의 컬럼
 * 전부를 처음 읽은 값으로 다시 써서 아래 시나리오에서 판매자 커서가 0으로 돌아간다.
 *
 * <p>테스트 트랜잭션 하나로는 "다른 트랜잭션이 그 사이에 커밋했다"를 만들 수 없어
 * {@code @Transactional}을 붙이지 않고 {@code REQUIRES_NEW}로 직접 겹친다
 * ({@link IntegrationTestSupport} 클래스 주석). 정리는 {@link #cleanUp()}에서 한다.
 */
class ChatRoomRejoinIntegrationTest extends IntegrationTestSupport {

    @Autowired
    ChatRoomRepository chatRoomRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    EntityManager entityManager;

    private Long roomId;
    private TransactionTemplate outer;
    private TransactionTemplate inner;

    @BeforeEach
    void setUp() {
        roomId = chatRoomRepository.saveAndFlush(
                ChatRoom.open("01J3ARSNIPREJOIN0000000001", 10L, 20L, 30L)).getId();
        outer = new TransactionTemplate(transactionManager);
        inner = new TransactionTemplate(transactionManager);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @AfterEach
    void cleanUp() {
        chatRoomRepository.deleteAllInBatch();
    }

    private void hideForBuyer() {
        outer.executeWithoutResult(status -> entityManager
                .createNativeQuery("UPDATE chat_rooms SET buyer_hidden_at = NOW(6) WHERE id = :id")
                .setParameter("id", roomId)
                .executeUpdate());
    }

    @Test
    @DisplayName("재진입 복구는 그 사이 다른 트랜잭션이 올린 판매자 읽음 커서를 옛 값으로 되돌리지 않는다")
    void rejoin_doesNotClobberCursorRaisedByAnotherTransaction() {
        hideForBuyer();

        outer.executeWithoutResult(status -> {
            // 이 트랜잭션이 방을 읽는 시점: 판매자 커서 0, 구매자는 숨김 상태.
            ChatRoom loaded = chatRoomRepository.findById(roomId).orElseThrow();
            assertThat(loaded.getBuyerHiddenAt()).isNotNull();
            assertThat(loaded.getSellerLastReadMessageId()).isZero();

            // 그 사이 다른 트랜잭션이 판매자 커서를 9로 올리고 커밋한다.
            inner.executeWithoutResult(s ->
                    chatRoomRepository.markSellerRead(roomId, 9L, Instant.now()));

            int updated = chatRoomRepository.rejoinAsBuyer(roomId, Instant.now());
            assertThat(updated).isEqualTo(1);
        });

        ChatRoom after = chatRoomRepository.findById(roomId).orElseThrow();
        assertThat(after.getBuyerHiddenAt()).isNull();
        assertThat(after.getSellerLastReadMessageId())
                .as("다른 트랜잭션이 올린 판매자 커서가 유지돼야 한다")
                .isEqualTo(9L);
    }

    @Test
    @DisplayName("이미 보이는 방에 복구를 걸면 0행이고, 몇 번을 걸어도 결과가 같다 (멱등)")
    void rejoin_onVisibleRoom_isNoop() {
        int first = outer.execute(status -> chatRoomRepository.rejoinAsBuyer(roomId, Instant.now()));
        int second = outer.execute(status -> chatRoomRepository.rejoinAsBuyer(roomId, Instant.now()));

        assertThat(first).isZero();
        assertThat(second).isZero();
        assertThat(chatRoomRepository.findById(roomId).orElseThrow().getBuyerHiddenAt()).isNull();
    }

    @Test
    @DisplayName("복구는 구매자 숨김만 건드리고 판매자 숨김은 그대로 둔다")
    void rejoin_leavesSellerHiddenUntouched() {
        outer.executeWithoutResult(status -> entityManager
                .createNativeQuery("UPDATE chat_rooms SET buyer_hidden_at = NOW(6), "
                        + "seller_hidden_at = NOW(6) WHERE id = :id")
                .setParameter("id", roomId)
                .executeUpdate());

        outer.executeWithoutResult(status ->
                chatRoomRepository.rejoinAsBuyer(roomId, Instant.now()));

        ChatRoom after = chatRoomRepository.findById(roomId).orElseThrow();
        assertThat(after.getBuyerHiddenAt()).isNull();
        assertThat(after.getSellerHiddenAt()).isNotNull();
    }
}
