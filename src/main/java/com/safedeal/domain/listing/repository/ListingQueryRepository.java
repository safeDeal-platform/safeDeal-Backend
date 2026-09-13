package com.safedeal.domain.listing.repository;

import com.safedeal.domain.listing.entity.Listing;

import java.util.List;

public interface ListingQueryRepository {

    /**
     * 공개 목록 조회. {@code size + 1}건을 돌려주므로 호출측이 초과분 유무로 hasNext를 판정하고
     * 잘라낸다.
     */
    List<Listing> findPublicPage(ListingSearchCondition condition);
}
