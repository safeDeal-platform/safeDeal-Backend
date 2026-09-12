package com.safedeal.domain.listing.service;

import com.safedeal.domain.listing.dto.ListingDetailResponse;
import com.safedeal.domain.listing.dto.ListingSummaryResponse;
import com.safedeal.domain.listing.entity.Category;
import com.safedeal.domain.listing.entity.Listing;
import com.safedeal.domain.listing.repository.CategoryRepository;
import com.safedeal.domain.listing.repository.ListingRepository;
import com.safedeal.domain.listing.repository.ListingSearchCondition;
import com.safedeal.global.exception.BusinessException;
import com.safedeal.global.exception.CommonErrorCode;
import com.safedeal.global.response.Base64CursorCodec;
import com.safedeal.global.response.CursorCodec;
import com.safedeal.global.response.CursorPayload;
import com.safedeal.global.response.CursorResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ListingQueryService {

    /** 목록 정렬 식별자. 커서에 실어, 정렬을 바꾼 채 옛 커서를 재사용하는 것을 구분한다. */
    public static final String SORT_KEY = "createdAt,desc";
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    private final ListingRepository listingRepository;
    private final CategoryRepository categoryRepository;
    private final CursorCodec cursorCodec;

    /**
     * 공개 목록.
     *
     * <p>size는 조용히 깎지 않고 400으로 거부한다 — 클라이언트가 자기가 얼마를 받았는지 알아야
     * 페이지네이션을 신뢰할 수 있다.
     *
     * <p>대분류 code로 걸러오면 하위 중분류로 펼쳐서 조회한다. 매물은 중분류에만 달리므로
     * 대분류 id로 직접 비교하면 아무것도 안 나온다.
     */
    public CursorResponse<ListingSummaryResponse> getListings(
            String cursor, Integer size, String categoryCode,
            String regionSido, String regionSigungu, Integer minPrice, Integer maxPrice) {

        int pageSize = resolveSize(size);
        validatePriceRange(minPrice, maxPrice);

        Instant lastCreatedAt = null;
        Long lastId = null;
        if (cursor != null && !cursor.isBlank()) {
            CursorPayload payload = cursorCodec.decode(cursor);
            if (!SORT_KEY.equals(payload.sort())) {
                throw new BusinessException(CommonErrorCode.INVALID_INPUT, "잘못된 커서입니다.");
            }
            lastCreatedAt = payload.lastCreatedAt();
            lastId = payload.lastId();
        }

        List<Listing> rows = listingRepository.findPublicPage(new ListingSearchCondition(
                resolveCategoryIds(categoryCode),
                regionSido, regionSigungu, minPrice, maxPrice,
                lastCreatedAt, lastId, pageSize));

        boolean hasNext = rows.size() > pageSize;
        List<Listing> page = hasNext ? rows.subList(0, pageSize) : rows;

        List<ListingSummaryResponse> items = new ArrayList<>(page.size());
        for (Listing row : page) {
            items.add(ListingSummaryResponse.from(row));
        }

        String nextCursor = null;
        if (hasNext) {
            Listing last = page.get(page.size() - 1);
            nextCursor = cursorCodec.encode(new CursorPayload(
                    Base64CursorCodec.VERSION, SORT_KEY,
                    last.getCreatedAt(), last.getId(), null, Instant.now()));
        }
        return new CursorResponse<>(items, nextCursor, hasNext);
    }

    /**
     * 공개 상세.
     *
     * <p>차단·삭제된 매물은 존재 자체를 알리지 않으려 404로 돌려준다(명세). 반면 <b>팔린 매물은
     * 열어준다</b> — 거래 당사자가 나중에 확인하고, 채팅·신고에서 넘어온 링크가 죽지 않아야 한다.
     */
    public ListingDetailResponse getListing(String publicId) {
        Listing listing = listingRepository.findByPublicIdAndDeletedAtIsNull(publicId)
                .filter(Listing::isViewable)
                .orElseThrow(() -> new BusinessException(
                        CommonErrorCode.RESOURCE_NOT_FOUND, "매물을 찾을 수 없습니다."));
        return ListingDetailResponse.from(listing);
    }

    private int resolveSize(Integer size) {
        if (size == null) {
            return DEFAULT_SIZE;
        }
        if (size <= 0 || size > MAX_SIZE) {
            throw new BusinessException(
                    CommonErrorCode.INVALID_INPUT, "size는 1 이상 %d 이하여야 합니다.".formatted(MAX_SIZE));
        }
        return size;
    }

    private void validatePriceRange(Integer minPrice, Integer maxPrice) {
        if (minPrice != null && maxPrice != null && minPrice > maxPrice) {
            throw new BusinessException(
                    CommonErrorCode.INVALID_INPUT, "최소 가격이 최대 가격보다 클 수 없습니다.");
        }
    }

    private List<Long> resolveCategoryIds(String categoryCode) {
        if (categoryCode == null || categoryCode.isBlank()) {
            return List.of();
        }
        Category category = categoryRepository.findByCode(categoryCode)
                .orElseThrow(() -> new BusinessException(
                        CommonErrorCode.INVALID_INPUT, "존재하지 않는 카테고리입니다."));
        if (category.isLeaf()) {
            if (!category.isActive()) {
                // 비활성 중분류의 매물은 목록에 내보내지 않는다. 대분류로 걸렀을 때 비활성 자식이
                // 빠지는 것(아래 findByParentIdAndActiveTrue)과 결과가 같아야 하기 때문이다 —
                // 그러지 않으면 "위에서 찾으면 없고 code를 직접 찍으면 나오는" 상태가 된다.
                // 400이 아니라 빈 결과인 이유: code 자체는 여전히 유효하고, 예전 링크·북마크로
                // 들어온 요청을 에러로 돌려보낼 이유가 없다. 등록(ListingCommandService)은
                // 새로 다는 것을 막아야 하므로 400이 맞고, 조회와 판단이 갈리는 것이 정상이다.
                return List.of(-1L);
            }
            return List.of(category.getId());
        }
        List<Category> children = categoryRepository.findByParentIdAndActiveTrue(category.getId());
        if (children.isEmpty()) {
            // 자식이 없는 대분류로 걸렀다면 결과가 없는 게 맞다. 빈 목록을 넘기면 조건 자체가
            // 빠져 전체가 나오므로, 존재할 수 없는 id를 넣어 빈 결과를 만든다.
            return List.of(-1L);
        }
        return children.stream().map(Category::getId).toList();
    }
}
