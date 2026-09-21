package com.safedeal.domain.chat.repository;

import com.safedeal.domain.chat.entity.ChatRoom;
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
}
