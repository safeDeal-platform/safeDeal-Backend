package com.safedeal.domain.listing.repository;

import com.safedeal.domain.listing.entity.Listing;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ListingRepository extends JpaRepository<Listing, Long>, ListingQueryRepository {

    /**
     * 공개 상세 조회용. 삭제되지 않은 행만 꺼낸다 — 소프트 삭제는 조건을 빠뜨리면 그대로
     * 노출되므로 조회 지점마다 명시한다.
     */
    Optional<Listing> findByPublicIdAndDeletedAtIsNull(String publicId);

    /**
     * 채팅방 재진입용 — 삭제된 매물도 꺼낸다. API 명세서 CHT-1이 "SOLD·삭제 매물은 기존
     * 방만 반환"을 요구하는데, 삭제된 매물의 기존 방을 찾으려면 삭제 여부와 무관하게
     * 매물을 조회할 수 있어야 한다. 이름에 {@code IncludingDeleted}를 박아, 소프트삭제
     * 조건이 빠진 것이 실수가 아니라 의도임을 드러낸다. 호출자는 채팅 재진입 경로 하나로
     * 한정한다 — 다른 곳에서 이 메서드를 쓰면 삭제된 매물이 노출될 수 있다.
     */
    @Query("select l from Listing l where l.publicId = :publicId")
    Optional<Listing> findByPublicIdIncludingDeleted(@Param("publicId") String publicId);
}
