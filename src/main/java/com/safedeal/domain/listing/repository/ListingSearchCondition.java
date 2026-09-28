package com.safedeal.domain.listing.repository;

import java.time.Instant;

/**
 * 목록 조회 조건. 커서(마지막 항목의 생성시각·id)와 필터를 함께 담는다. 필터는 전부 null
 * 허용이며, null이면 그 조건을 걸지 않는다. size는 hasNext 판정을 위해 리포지토리가
 * +1건을 조회하는 데 쓰인다.
 */
public record ListingSearchCondition(
        java.util.List<Long> categoryIds,
        String regionSido,
        String regionSigungu,
        Integer minPrice,
        Integer maxPrice,
        Instant lastCreatedAt,
        Long lastId,
        int size
) {
}
