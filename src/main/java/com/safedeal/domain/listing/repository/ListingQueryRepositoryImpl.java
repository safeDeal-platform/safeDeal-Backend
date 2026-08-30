package com.safedeal.domain.listing.repository;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.safedeal.domain.listing.entity.Listing;
import com.safedeal.domain.listing.entity.ListingStatus;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.List;

import static com.safedeal.domain.listing.entity.QListing.listing;

/**
 * 목록 조회는 필터 조합이 요청마다 달라 QueryDSL로 짠다.
 *
 * <p>오프셋 대신 keyset(커서)을 쓰는 이유: {@code OFFSET n}은 앞의 n건을 세고 버리므로 뒤로 갈수록
 * 느려지고, 조회 중 앞쪽에 새 매물이 들어오면 항목이 밀려 중복·누락이 생긴다.
 *
 * <p>노출 조건은 <b>허용 목록</b>이다. "BLOCKED가 아닌 것"처럼 거부 목록으로 짜면 나중에 상태가
 * 하나 추가될 때 검토 없이 자동으로 노출된다.
 */
@RequiredArgsConstructor
public class ListingQueryRepositoryImpl implements ListingQueryRepository {

    private final JPAQueryFactory queryFactory;

    @Override
    public List<Listing> findPublicPage(ListingSearchCondition c) {
        return queryFactory
                .selectFrom(listing)
                .where(
                        listing.deletedAt.isNull(),
                        listing.status.eq(ListingStatus.ACTIVE),
                        categoryIn(c.categoryIds()),
                        eqSido(c.regionSido()),
                        eqSigungu(c.regionSigungu()),
                        priceGoe(c.minPrice()),
                        priceLoe(c.maxPrice()),
                        afterCursor(c.lastCreatedAt(), c.lastId()))
                .orderBy(listing.createdAt.desc(), listing.id.desc())
                .limit(c.size() + 1L)
                .fetch();
    }

    /**
     * 커서 이후 구간. {@code created_at < :c OR (created_at = :c AND id < :id)} —
     * 같은 시각에 만들어진 행이 있어도 id로 순서가 확정되므로 건너뛰거나 겹치지 않는다.
     */
    private BooleanExpression afterCursor(Instant lastCreatedAt, Long lastId) {
        if (lastCreatedAt == null || lastId == null) {
            return null;
        }
        return listing.createdAt.lt(lastCreatedAt)
                .or(listing.createdAt.eq(lastCreatedAt).and(listing.id.lt(lastId)));
    }

    private BooleanExpression categoryIn(List<Long> categoryIds) {
        return (categoryIds == null || categoryIds.isEmpty())
                ? null : listing.category.id.in(categoryIds);
    }

    private BooleanExpression eqSido(String sido) {
        return (sido == null || sido.isBlank()) ? null : listing.regionSido.eq(sido);
    }

    private BooleanExpression eqSigungu(String sigungu) {
        return (sigungu == null || sigungu.isBlank()) ? null : listing.regionSigungu.eq(sigungu);
    }

    private BooleanExpression priceGoe(Integer minPrice) {
        return minPrice == null ? null : listing.price.goe(minPrice);
    }

    private BooleanExpression priceLoe(Integer maxPrice) {
        return maxPrice == null ? null : listing.price.loe(maxPrice);
    }
}
