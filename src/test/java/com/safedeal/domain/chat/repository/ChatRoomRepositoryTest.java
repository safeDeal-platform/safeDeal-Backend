package com.safedeal.domain.chat.repository;

import com.safedeal.domain.chat.entity.ChatRoom;
import com.safedeal.testsupport.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 파생 쿼리와 UNIQUE(buyer_id, listing_id)가 실제 MySQL에서 도는지 확인한다.
 */
@Transactional
class ChatRoomRepositoryTest extends IntegrationTestSupport {

    @Autowired
    ChatRoomRepository chatRoomRepository;

    @Test
    @DisplayName("buyer_id, listing_id로 조회된다")
    void findsByBuyerAndListing() {
        ChatRoom saved = chatRoomRepository.saveAndFlush(
                ChatRoom.open("01J3ARSNIPROOM0000000001", 10L, 20L, 30L));

        Optional<ChatRoom> found = chatRoomRepository.findByBuyerIdAndListingId(20L, 10L);

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
    }

    @Test
    @DisplayName("다른 구매자·다른 매물 조합은 조회되지 않는다")
    void doesNotFindOtherCombinations() {
        chatRoomRepository.saveAndFlush(ChatRoom.open("01J3ARSNIPROOM0000000001", 10L, 20L, 30L));

        assertThat(chatRoomRepository.findByBuyerIdAndListingId(99L, 10L)).isEmpty();
        assertThat(chatRoomRepository.findByBuyerIdAndListingId(20L, 99L)).isEmpty();
    }

    @Test
    @DisplayName("같은 (buyer_id, listing_id) 조합을 두 번 저장하면 UNIQUE 위반이 난다 (동시 클릭 최종 방어)")
    void sameBuyerAndListingViolatesUniqueConstraint() {
        chatRoomRepository.saveAndFlush(
                ChatRoom.open("01J3ARSNIPROOM0000000001", 10L, 20L, 30L));

        assertThatThrownBy(() -> chatRoomRepository.saveAndFlush(
                ChatRoom.open("01J3ARSNIPROOM0000000002", 10L, 20L, 30L)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("구매자·판매자 둘 다 public_id + 참여자 조건으로 찾아진다")
    void findsByPublicIdAndParticipant_forBothSides() {
        ChatRoom saved = chatRoomRepository.saveAndFlush(
                ChatRoom.open("01J3ARSNIPROOM0000000001", 10L, 20L, 30L));

        assertThat(chatRoomRepository.findByPublicIdAndParticipant(saved.getPublicId(), 20L))
                .isPresent();
        assertThat(chatRoomRepository.findByPublicIdAndParticipant(saved.getPublicId(), 30L))
                .isPresent();
    }

    @Test
    @DisplayName("참여자가 아니면 방이 존재해도 빈 값이다 (존재 여부를 알려주지 않는다)")
    void findByPublicIdAndParticipant_nonParticipant_isEmpty() {
        ChatRoom saved = chatRoomRepository.saveAndFlush(
                ChatRoom.open("01J3ARSNIPROOM0000000001", 10L, 20L, 30L));

        assertThat(chatRoomRepository.findByPublicIdAndParticipant(saved.getPublicId(), 999L))
                .isEmpty();
    }

    @Test
    @DisplayName("구매자 읽음 커서는 기존 값보다 클 때만 전진한다")
    void markBuyerRead_advancesOnlyWhenGreater() {
        ChatRoom saved = chatRoomRepository.saveAndFlush(
                ChatRoom.open("01J3ARSNIPROOM0000000001", 10L, 20L, 30L));
        assertThat(saved.getBuyerLastReadMessageId()).isZero();

        int updated = chatRoomRepository.markBuyerRead(saved.getId(), 5L, Instant.now());
        assertThat(updated).isEqualTo(1);

        int noop = chatRoomRepository.markBuyerRead(saved.getId(), 5L, Instant.now());
        assertThat(noop).isZero();

        int regress = chatRoomRepository.markBuyerRead(saved.getId(), 3L, Instant.now());
        assertThat(regress).isZero();

        ChatRoom reloaded = chatRoomRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getBuyerLastReadMessageId()).isEqualTo(5L);
    }

    @Test
    @DisplayName("판매자 읽음 커서는 구매자 커서와 독립적으로 전진한다")
    void markSellerRead_isIndependentOfBuyerCursor() {
        ChatRoom saved = chatRoomRepository.saveAndFlush(
                ChatRoom.open("01J3ARSNIPROOM0000000001", 10L, 20L, 30L));

        chatRoomRepository.markBuyerRead(saved.getId(), 5L, Instant.now());
        int updated = chatRoomRepository.markSellerRead(saved.getId(), 7L, Instant.now());

        assertThat(updated).isEqualTo(1);
        ChatRoom reloaded = chatRoomRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getBuyerLastReadMessageId()).isEqualTo(5L);
        assertThat(reloaded.getSellerLastReadMessageId()).isEqualTo(7L);
    }
}
