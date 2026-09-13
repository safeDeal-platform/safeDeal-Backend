package com.safedeal.domain.listing.repository;

import java.time.Instant;

/**
 * 목록 조회 조건. 커서(마지막 항목의 생성시각·id)와 필터를 함께 담는다.
 *
 * <p>필터는 전부 null 허용이고, null이면 그 조건을 걸지 않는다.
 *
 * @param categoryId      중분류 id. 대분류로 검색한 경우 서비스가 하위 중분류 id로 펼쳐서 넣는다.
 * @param categoryIds     대분류 검색 시 펼친 중분류 id 목록. 비어 있으면 categoryId를 쓴다.
 * @param lastCreatedAt   커서 — 이전 페이지 마지막 항목의 생성 시각
 * @param lastId          커서 — 동률 tie-break용 id
 * @param size            가져올 개수(hasNext 판정을 위해 리포지토리가 +1건을 조회한다)
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
