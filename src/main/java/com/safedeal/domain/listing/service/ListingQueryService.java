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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
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
        String fingerprint = filterFingerprint(categoryCode, regionSido, regionSigungu, minPrice, maxPrice);

        Instant lastCreatedAt = null;
        Long lastId = null;
        if (cursor != null && !cursor.isBlank()) {
            CursorPayload payload = cursorCodec.decode(cursor);
            if (!SORT_KEY.equals(payload.sort())) {
                throw new BusinessException(CommonErrorCode.INVALID_INPUT, "잘못된 커서입니다.");
            }
            // 커서의 경계(createdAt, id)는 그 커서를 발급한 검색 조건 안에서만 의미가 있다. 조건을 바꾼 채
            // 옛 커서를 쓰면 새 조건과 옛 경계가 따로 걸려, 경계보다 최신인 새 조건의 매물이 에러 없이
            // 통째로 빠진다. 조용히 빠지는 것보다 거부해서 클라이언트가 첫 페이지부터 다시 부르게 한다.
            if (!fingerprint.equals(payload.filterFingerprint())) {
                throw new BusinessException(CommonErrorCode.INVALID_INPUT,
                        "검색 조건이 바뀌어 이 커서를 사용할 수 없습니다. 첫 페이지부터 다시 조회하세요.");
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
                    last.getCreatedAt(), last.getId(), fingerprint, Instant.now()));
        }
        return new CursorResponse<>(items, nextCursor, hasNext);
    }

    /**
     * 목록 필터의 지문. 커서에 실어, 다음 요청의 필터가 발급 때와 같은지 비교한다.
     *
     * <p><b>조회 결과를 가르는 입력만</b> 넣는다. size는 빠진다 — 한 페이지 개수만 바뀌어도 경계는
     * 어긋나지 않는다. 빈 문자열은 null과 같게 취급한다 — 리포지토리가 둘을 똑같이 "필터 없음"으로
     * 처리하므로({@code ListingQueryRepositoryImpl}의 isBlank 검사), 지문이 둘을 가르면 결과가 같은
     * 요청을 거부하게 된다. 값의 앞뒤 공백은 자르지 않는다 — 리포지토리가 그대로 비교해 결과가 달라진다.
     *
     * <p>커서는 비밀이 아니고 클라이언트가 지문을 고쳐 보낼 수도 있다. 이것은 공격 방어가 아니라
     * "필터를 바꾸면서 커서를 초기화하지 않은" 실수를 드러내는 장치다 — 고쳐 보내봐야 얻는 것은
     * 이미 공개된 매물 목록뿐이다.
     */
    static String filterFingerprint(String categoryCode, String regionSido, String regionSigungu,
                                    Integer minPrice, Integer maxPrice) {
        // 값마다 길이를 앞에 붙여 이어 붙인다. 구분자만 쓰면 값 안에 구분자가 들어왔을 때
        // ("a|b","c")와 ("a","b|c")처럼 서로 다른 조건이 같은 문자열이 된다.
        StringBuilder canonical = new StringBuilder();
        for (String value : new String[]{
                categoryCode, regionSido, regionSigungu,
                minPrice == null ? null : minPrice.toString(),
                maxPrice == null ? null : maxPrice.toString()}) {
            String normalized = (value == null || value.isBlank()) ? "" : value;
            canonical.append(normalized.length()).append(':').append(normalized);
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            // 우연한 충돌만 피하면 되므로 앞 12바이트(URL-safe 16자)로 커서 길이를 줄인다.
            return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(hash, 12));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 모든 자바 런타임이 반드시 제공해야 하는 알고리즘이라 여기 올 수 없다.
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다", e);
        }
    }

    /**
     * 공개 상세.
     *
     * <p>차단·삭제된 매물은 존재 자체를 알리지 않으려 404로 돌려준다(명세). 반면 <b>팔린 매물은
     * 열어준다</b> — 거래 당사자가 나중에 확인하고, 채팅·신고에서 넘어온 링크가 죽지 않아야 한다.
     *
     * <p><b>카테고리가 비활성으로 내려간 매물도 같은 이유로 열어준다.</b> 목록 조회
     * ({@link #resolveCategoryIds})는 비활성 분류를 걸러 검색 결과에서 빼지만, 상세는 막지 않는다 —
     * 내려간 것은 분류일 뿐 매물은 여전히 판매중이고, 여기서 404를 주면 채팅으로 흥정하던 구매자와
     * 판매자 본인이 자기 매물을 못 보게 된다. "검색에는 안 뜨지만 링크가 있으면 보인다"가 의도다.
     * 목록과 상세의 이 판단 차이는 실수가 아니므로 맞추려 하지 말 것.
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
