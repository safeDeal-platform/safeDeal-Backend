package com.safedeal.domain.chat.repository;

import com.safedeal.domain.chat.entity.ChatRoom;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 조건이 고정된 정적 쿼리라 QueryDSL 없이 파생 쿼리로 충분하다(알림 리포지토리와 같은 전략).
 */
public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

    /** 조건 순서를 UNIQUE 컬럼 순서(buyer_id, listing_id)와 맞춘다. */
    Optional<ChatRoom> findByBuyerIdAndListingId(Long buyerId, Long listingId);
}
