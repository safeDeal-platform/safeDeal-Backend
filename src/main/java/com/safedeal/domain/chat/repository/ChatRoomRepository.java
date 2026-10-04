package com.safedeal.domain.chat.repository;

import com.safedeal.domain.chat.entity.ChatRoom;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    /**
     * public_id로 방을 찾되, 참여자(구매자·판매자) 조건을 쿼리 자체에 박아둔다. 참여자가
     * 아니면 "권한 없음"이 아니라 "없음"으로 응답한다 — 알림 도메인의
     * {@code findByIdAndUserId}와 같은 원칙(존재 여부 자체를 알려주지 않는다).
     */
    @Query("select r from ChatRoom r where r.publicId = :publicId "
            + "and (r.buyerId = :userId or r.sellerId = :userId)")
    Optional<ChatRoom> findByPublicIdAndParticipant(
            @Param("publicId") String publicId, @Param("userId") Long userId);

    /**
     * 구매자 쪽 숨김을 되돌린다(API 명세서 CHT-1 "숨김 상태였다면 재진입 시 복구").
     *
     * <p><b>엔티티를 읽어 필드를 바꾸는(더티체킹) 방식이 아니라 이 조건부 UPDATE다.</b>
     * 더티체킹은 그 행의 컬럼 <i>전부</i>를 처음 읽은 값으로 다시 쓴다 — 그 사이 다른
     * 트랜잭션이 올려둔 읽음 커서({@link #markSellerRead})가 옛 값으로 되돌아간다(lost update).
     * 이 쿼리는 {@code buyer_hidden_at} 한 컬럼만 건드리고, 이미 보이는 상태면 0행이라
     * 몇 번을 호출해도 결과가 같다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ChatRoom r set r.buyerHiddenAt = null, r.updatedAt = :now "
            + "where r.id = :roomId and r.buyerHiddenAt is not null")
    int rejoinAsBuyer(@Param("roomId") Long roomId, @Param("now") Instant now);

    /**
     * 구매자 읽음 커서를 조건부로 전진시킨다(정책 "채팅 — 재민": WHERE last_read < 새값,
     * 역행·위변조 방지). 영향 행이 0이면 이미 그 값 이상으로 읽은 상태라는 뜻이라 실패가
     * 아니라 정상적인 no-op이다.
     *
     * <p>벌크 UPDATE는 {@code @LastModifiedDate}가 안 타므로 {@code updated_at}을 직접
     * 채운다({@code EmailVerificationTokenRepository.markUsed}와 같은 이유).
     *
     * <p>{@code clearAutomatically = true}도 함께 둔다 — 벌크 UPDATE는 영속성 컨텍스트에
     * 이미 로드된 엔티티(자바 객체)를 안 건드리고 DB 행만 바꾼다. 이 플래그 없이 같은
     * 트랜잭션에서 다시 조회하면 갱신 전의 낡은 객체가 캐시에서 그대로 돌아온다.
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

    /**
     * 읽음 커서 재조회 전용(CONC-2 회귀 수정). {@code markBuyerRead}/{@code markSellerRead}가
     * 0행이어서 "지금 실제 커서가 뭔지" 다시 확인해야 할 때만 쓴다.
     *
     * <p><b>왜 평범한 {@code findById}로는 안 되는가:</b> MySQL InnoDB의 기본 격리수준인
     * REPEATABLE READ에서 평범한 SELECT(consistent read)는 그 트랜잭션이 맨 처음 읽은 시점의
     * 스냅샷만 본다. 같은 트랜잭션 안에서 그 사이 다른 트랜잭션이 커밋해도 평범한 재조회로는
     * 그 변경이 안 보인다 — 트랜잭션 시작할 때 찍어 둔 사진을 계속 들고 있는 것과 같다.
     * {@code SELECT ... LOCK IN SHARE MODE}(현재 읽기, current read)만 "지금 실제로 커밋된
     * 값"을 본다. {@code ChatReadCommandServiceIntegrationTest}가 이 차이를 실제 MySQL로
     * 고정한다.
     *
     * <p><b>커서 단조 증가와의 관계:</b> {@code buyerLastReadMessageId}/
     * {@code sellerLastReadMessageId}는 조건부 UPDATE(`< :messageId`)로만 바뀌어 절대
     * 감소하지 않는다. 그래서 "지금 실제 커서"를 다시 읽기만 하면 항상 정답이다 — 이 메서드는
     * 그 재조회를 REPEATABLE READ 스냅샷에 걸리지 않게 해 줄 뿐, 커서 계산 로직 자체를
     * 바꾸지 않는다.
     *
     * <p><b>추가 잠금 경합이 없는 이유:</b> 이 메서드가 호출되는 시점(0행)에도 직전의
     * {@code markBuyerRead}/{@code markSellerRead} UPDATE 시도 자체가 이미 이 행에 대한
     * X락을 걸었다가 커밋 조건 불일치로 0행 갱신한 상태다 — InnoDB는 조건에 안 맞아 0행이어도
     * 검사한 행에 락을 남긴다. 이 공유 잠금 읽기는 그 위에 얹히는 것이라 별도의 새 경합을
     * 만들지 않는다.
     *
     * <p>{@code markBuyerRead}/{@code markSellerRead}의 {@code clearAutomatically = true}에
     * 의존한다 — 그게 없으면 영속성 컨텍스트에 캐시된 낡은 엔티티가 이 조회보다 먼저 반환된다.
     *
     * <p><b>{@code UserRepository#findForTrustScoreUpdate}와 목적이 다르다:</b> 그쪽은
     * {@code PESSIMISTIC_WRITE}로 동시 누적 계산을 직렬화해 "쓰기"를 보호한다. 이쪽은 쓰기가
     * 아니라 "방금 일어난 다른 트랜잭션의 쓰기를 읽기 쪽이 못 보는 문제"를 고치는 것이라
     * 공유 잠금({@code PESSIMISTIC_READ})으로 충분하다.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select r from ChatRoom r where r.id = :id")
    Optional<ChatRoom> findForReadCursorRecheck(@Param("id") Long id);
}
