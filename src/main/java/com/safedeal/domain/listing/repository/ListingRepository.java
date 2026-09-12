package com.safedeal.domain.listing.repository;

import com.safedeal.domain.listing.entity.Listing;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ListingRepository extends JpaRepository<Listing, Long>, ListingQueryRepository {

    /**
     * 공개 상세 조회용. 삭제되지 않은 행만 꺼낸다 — 소프트 삭제는 조건을 빠뜨리면 그대로
     * 노출되므로 조회 지점마다 명시한다.
     */
    Optional<Listing> findByPublicIdAndDeletedAtIsNull(String publicId);

    Optional<Listing> findByPublicId(String publicId);
}
