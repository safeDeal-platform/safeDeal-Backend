package com.safedeal.domain.listing.repository;

import com.safedeal.domain.listing.entity.Listing;
import com.safedeal.domain.listing.entity.ListingStatus;
import com.safedeal.domain.listing.entity.SoldSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

public interface ListingRepository extends JpaRepository<Listing, Long>, ListingQueryRepository {

    /**
     * 공개 상세 조회용. 삭제되지 않은 행만 꺼낸다 — 소프트 삭제는 조건을 빠뜨리면 그대로
     * 노출되므로 조회 지점마다 명시한다.
     */
    Optional<Listing> findByPublicIdAndDeletedAtIsNull(String publicId);

    Optional<Listing> findByPublicId(String publicId);

    /**
     * 판매자 버튼으로 판매완료 처리.
     *
     * <p>전이표 검증만으로는 부족해 조건부 UPDATE로 마무리한다 — 서버 두 대가 동시에 다른
     * 전이를 시도하면 각자 자기 메모리에서 검증해 둘 다 통과하고, 마지막에 쓴 쪽이 이겨
     * 데이터가 오염된다. WHERE에 기대 상태를 걸면 DB가 한 쪽만 성공시킨다.
     *
     * <p>영향 행이 0이면 이미 팔렸거나, 남의 매물이거나, 공개 상태가 아니라는 뜻이다.
     *
     * <p>MANUAL로 남기는 이유: 결제로 팔린 건과 되돌리기 규칙이 다르고, 가격 통계는 결제
     * 건만 원료로 써야 가짜 거래로 시세를 조작할 수 없다.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Listing l "
            + "SET l.status = :soldStatus, l.soldSource = :manualSource, l.soldAt = :now "
            + "WHERE l.id = :id AND l.sellerId = :sellerId "
            + "AND l.status = :activeStatus AND l.deletedAt IS NULL")
    int markSoldByOwner(@Param("id") Long id,
                        @Param("sellerId") Long sellerId,
                        @Param("now") Instant now,
                        @Param("activeStatus") ListingStatus activeStatus,
                        @Param("soldStatus") ListingStatus soldStatus,
                        @Param("manualSource") SoldSource manualSource);

    /**
     * 판매자가 실수로 누른 판매완료를 되돌린다.
     *
     * <p>결제로 팔린 건은 환불 절차로만 풀려야 통계 정합이 유지되므로 MANUAL만 허용하고,
     * 시간도 제한한다. 영향 행이 0이면 결제 건이거나 제한 시간이 지났거나 이미 되돌려진 것이다.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Listing l "
            + "SET l.status = :activeStatus, l.soldSource = NULL, l.soldAt = NULL "
            + "WHERE l.id = :id AND l.sellerId = :sellerId "
            + "AND l.status = :soldStatus AND l.soldSource = :manualSource "
            + "AND l.soldAt > :threshold AND l.deletedAt IS NULL")
    int restoreManualSoldByOwner(@Param("id") Long id,
                                 @Param("sellerId") Long sellerId,
                                 @Param("threshold") Instant threshold,
                                 @Param("activeStatus") ListingStatus activeStatus,
                                 @Param("soldStatus") ListingStatus soldStatus,
                                 @Param("manualSource") SoldSource manualSource);

    /**
     * 조회수 +1. 판매자 본인은 세지 않는다.
     *
     * <p>엔티티 더티체킹이 아니라 벌크 UPDATE인 이유: 더티체킹은 {@code @Version}을 함께
     * 올려, 남이 상세를 열어본 것만으로 판매자의 수정이 낙관적 락 충돌로 실패한다.
     *
     * <p>{@code viewerId}가 null이면(비로그인) 판매자 비교를 건너뛰고 무조건 센다.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Listing l SET l.viewCount = l.viewCount + 1 "
            + "WHERE l.publicId = :publicId AND l.deletedAt IS NULL "
            + "AND (:viewerId IS NULL OR l.sellerId <> :viewerId)")
    int increaseViewCount(@Param("publicId") String publicId, @Param("viewerId") Long viewerId);

    /** 전이에 쓰는 상태 상수를 호출부가 매번 넘기지 않도록 감싼다. */
    default int markSoldByOwner(Long id, Long sellerId, Instant now) {
        return markSoldByOwner(id, sellerId, now,
                ListingStatus.ACTIVE, ListingStatus.SOLD, SoldSource.MANUAL);
    }

    default int restoreManualSoldByOwner(Long id, Long sellerId, Instant threshold) {
        return restoreManualSoldByOwner(id, sellerId, threshold,
                ListingStatus.ACTIVE, ListingStatus.SOLD, SoldSource.MANUAL);
    }
}
