package com.safedeal.domain.listing.repository;

import com.safedeal.domain.listing.entity.Listing;
import com.safedeal.domain.listing.entity.ListingFavorite;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ListingFavoriteRepository extends JpaRepository<ListingFavorite, Long> {

    Optional<ListingFavorite> findByUserIdAndListing(Long userId, Listing listing);

    /**
     * 찜을 넣되, 이미 있으면 아무것도 하지 않는다.
     *
     * <p><b>"있는지 보고 없으면 저장"으로 짜면 안 된다.</b> 하트 연타로 두 요청이 동시에
     * 들어오면 둘 다 없다고 판정하고 둘 다 INSERT를 시도한다. 그때 뒤쪽이 UNIQUE 제약에
     * 걸리는데, 그 예외를 서비스에서 잡아도 소용이 없다 — Hibernate가 예외를 변환하면서
     * 세션을 rollback-only로 표시해, 성공을 리턴해도 커밋 시점에
     * {@code UnexpectedRollbackException}이 터져 500이 나간다. 실제로 8스레드 테스트에서
     * 재현했다.
     *
     * <p>그래서 판정과 삽입을 한 구문으로 합쳐 예외 자체를 만들지 않는다. 중복이면
     * {@code user_id = user_id}라 실제로 바뀌는 값이 없고 MySQL은 0행을 돌려준다.
     *
     * <p><b>{@code notify_base_price}를 갱신하지 않는 것이 핵심이다.</b> 재찜 때 기준가를
     * 다시 쓰면, 판매자가 값을 올린 뒤 사용자가 하트를 다시 누르는 것만으로 기준가가 올라가
     * "지금까지의 최저가 대비 인하" 정책이 무너진다.
     *
     * <p>네이티브 구문이라 JPA 감사(auditing)를 타지 않으므로 시각을 직접 넘긴다.
     *
     * <p><b>반환값을 두지 않는다.</b> MySQL Connector/J가 기본으로 {@code CLIENT_FOUND_ROWS}를
     * 켜기 때문에 중복이어도 영향 행 수가 1로 올라와, 신규와 중복을 구분하는 용도로 쓸 수 없다.
     * 구분되는 것처럼 보이는 값을 돌려주면 나중에 그걸 믿고 분기하는 코드가 생긴다.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "INSERT INTO listing_favorites "
            + "(user_id, listing_id, notify_base_price, created_at, updated_at) "
            + "VALUES (:userId, :listingId, :notifyBasePrice, :now, :now) "
            + "ON DUPLICATE KEY UPDATE user_id = user_id",
            nativeQuery = true)
    void insertIfAbsent(@Param("userId") Long userId,
                        @Param("listingId") Long listingId,
                        @Param("notifyBasePrice") int notifyBasePrice,
                        @Param("now") Instant now);

    /**
     * 찜을 지운다. 없으면 0행이고 그게 정상이다(해제는 멱등).
     *
     * <p>엔티티를 조회해서 {@code delete(entity)}로 지우면 안 된다. SELECT와 DELETE 사이에
     * 다른 요청이 같은 행을 지우면 Hibernate가 영향 행 수를 검증하다 실패해
     * {@code ObjectOptimisticLockingFailureException} → 409가 나간다. 해제 멱등 정책과
     * 어긋나며 이 역시 8스레드 테스트에서 재현했다. 단일 DELETE 구문에는 그 창이 없다.
     *
     * @return 실제로 지운 행 수(0 또는 1)
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM ListingFavorite f WHERE f.userId = :userId AND f.listing.id = :listingId")
    int deleteByUserIdAndListingId(@Param("userId") Long userId,
                                   @Param("listingId") Long listingId);

    /**
     * 내 찜 목록. 매물을 함께 가져온다 — 목록 한 줄마다 제목·가격·상태가 필요해 지연 로딩으로
     * 두면 행 수만큼 추가 쿼리가 나간다.
     *
     * <p>매물이 팔렸거나 삭제·차단됐어도 <b>행을 빼지 않는다</b>(정책). 사용자에게는 "현재
     * 거래할 수 없는 상품"으로 보여야지, 조용히 사라지면 찜한 기억과 화면이 어긋난다.
     *
     * <p>{@code size + 1}건을 돌려주므로 호출측이 초과분 유무로 hasNext를 판정하고 잘라낸다.
     */
    @Query("SELECT f FROM ListingFavorite f JOIN FETCH f.listing "
            + "WHERE f.userId = :userId "
            + "AND (:lastCreatedAt IS NULL "
            + "     OR f.createdAt < :lastCreatedAt "
            + "     OR (f.createdAt = :lastCreatedAt AND f.id < :lastId)) "
            + "ORDER BY f.createdAt DESC, f.id DESC")
    List<ListingFavorite> findPage(@Param("userId") Long userId,
                                   @Param("lastCreatedAt") Instant lastCreatedAt,
                                   @Param("lastId") Long lastId,
                                   Pageable pageable);
}
