package com.safedeal.domain.chat.repository;

import com.safedeal.domain.chat.entity.ChatRoom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

/**
 * 조건이 고정된 정적 쿼리라 QueryDSL 없이 파생 쿼리로 충분하다(알림 리포지토리와 같은 전략).
 */
public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

    /** 조건 순서를 UNIQUE 컬럼 순서(buyer_id, listing_id)와 맞춘다. */
    Optional<ChatRoom> findByBuyerIdAndListingId(Long buyerId, Long listingId);

    /** public_id로 방을 찾되 참여자 조건도 함께 건다 — 참여자가 아니면 "권한 없음"이 아니라 "없음"으로 응답한다. */
    @Query("select r from ChatRoom r where r.publicId = :publicId "
            + "and (r.buyerId = :userId or r.sellerId = :userId)")
    Optional<ChatRoom> findByPublicIdAndParticipant(
            @Param("publicId") String publicId, @Param("userId") Long userId);

    /**
     * 구매자 쪽 숨김을 되돌린다. 값을 읽어와 고치는 방식이면 그 사이 다른 트랜잭션이 올린
     * 읽음 커서가 예전 값으로 돌아가 버려서, 이 조건부 UPDATE만 쓴다. 이미 보이는 상태면
     * 0행이라 몇 번을 불러도 결과가 같다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ChatRoom r set r.buyerHiddenAt = null, r.updatedAt = :now "
            + "where r.id = :roomId and r.buyerHiddenAt is not null")
    int rejoinAsBuyer(@Param("roomId") Long roomId, @Param("now") Instant now);

    /**
     * 구매자 읽음 커서를 조건부로 전진시킨다(WHERE last_read < 새값, 역행 방지). 영향받은
     * 행이 0개면 이미 그 값까지 읽은 상태라 아무 것도 바꾸지 않고 그대로 끝낸다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ChatRoom r set r.buyerLastReadMessageId = :messageId, r.updatedAt = :now "
            + "where r.id = :roomId and r.buyerLastReadMessageId < :messageId")
    int markBuyerRead(@Param("roomId") Long roomId, @Param("messageId") Long messageId,
            @Param("now") Instant now);

    /** 판매자 쪽. JPQL은 컬럼명을 파라미터화할 수 없어 메서드를 나눈다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ChatRoom r set r.sellerLastReadMessageId = :messageId, r.updatedAt = :now "
            + "where r.id = :roomId and r.sellerLastReadMessageId < :messageId")
    int markSellerRead(@Param("roomId") Long roomId, @Param("messageId") Long messageId,
            @Param("now") Instant now);
}
