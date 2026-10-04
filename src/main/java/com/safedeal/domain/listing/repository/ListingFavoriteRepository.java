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

    /**
     * 한 사용자의 특정 매물 찜 행. 등록·해제는 조회 없이 한 구문으로 처리하므로 운영 경로에는
     * 호출자가 없고, 지금은 저장된 기준가를 확인하는 테스트가 쓴다.
     */
    Optional<ListingFavorite> findByUserIdAndListing(Long userId, Listing listing);

    /**
     * 찜을 넣되, 이미 있으면 아무것도 하지 않는다.
     *
     * <p><b>"있는지 보고 없으면 저장"으로 짜면 안 된다.</b> 동시 요청 둘 다 없다고 판정해 둘 다
     * INSERT를 시도하면 뒤쪽이 UNIQUE 제약에 걸리고, 그 예외를 잡아도 Hibernate가 세션을
     * rollback-only로 표시해 커밋 시점에 {@code UnexpectedRollbackException} → 500이 난다
     * (8스레드 테스트로 재현). 그래서 판정과 삽입을 한 구문으로 합친다 — 중복이면
     * {@code user_id = user_id}라 실제 변경이 없다.
     *
     * <p><b>{@code notify_base_price}는 갱신하지 않는다</b> — 연타로 몰려도 기준가가 흔들리지
     * 않게 하기 위해서다. 해제(하드 삭제) 후 다시 찜하면 새 행이라 그 시점 가격에서 다시
     * 시작한다.
     *
     * <p>네이티브 구문이라 JPA 감사를 타지 않아 시각을 직접 넘긴다.
     *
     * <p><b>반환값을 두지 않는다</b> — MySQL Connector/J가 기본으로 {@code CLIENT_FOUND_ROWS}를
     * 켜서 중복이어도 영향 행 수가 1로 나와 신규·중복 구분에 쓸 수 없다.
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
     * <p>엔티티를 조회해 {@code delete(entity)}로 지우면 안 된다 — SELECT와 DELETE 사이에
     * 다른 요청이 같은 행을 지우면 Hibernate가 영향 행 수 검증에 실패해
     * {@code ObjectOptimisticLockingFailureException} → 409가 난다(8스레드 테스트로 재현).
     * 단일 DELETE 구문에는 그 창이 없다.
     *
     * <p>매물을 먼저 조회하지 않고 공개 ID를 서브쿼리로 푼다 — 조회 결과로 응답을 가르면
     * "삭제된 id는 200, 없던 id는 404"처럼 매물 존재 여부가 새어 나간다.
     *
     * @return 실제로 지운 행 수(0 또는 1)
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM ListingFavorite f WHERE f.userId = :userId AND f.listing.id IN "
            + "(SELECT l.id FROM Listing l WHERE l.publicId = :publicId)")
    int deleteByUserIdAndListingPublicId(@Param("userId") Long userId,
                                         @Param("publicId") String publicId);

    /**
     * 내 찜 목록. 매물을 함께 가져온다 — 지연 로딩으로 두면 행 수만큼 추가 쿼리가 나간다.
     *
     * <p>매물이 팔렸거나 삭제·차단돼도 행을 빼지 않는다(정책) — 조용히 사라지면 찜한 기억과
     * 화면이 어긋난다.
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
