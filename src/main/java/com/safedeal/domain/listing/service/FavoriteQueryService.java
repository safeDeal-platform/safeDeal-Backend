package com.safedeal.domain.listing.service;

import com.safedeal.domain.listing.dto.FavoriteSummaryResponse;
import com.safedeal.domain.listing.entity.ListingFavorite;
import com.safedeal.domain.listing.repository.ListingFavoriteRepository;
import com.safedeal.global.exception.BusinessException;
import com.safedeal.global.exception.CommonErrorCode;
import com.safedeal.global.response.Base64CursorCodec;
import com.safedeal.global.response.CursorCodec;
import com.safedeal.global.response.CursorPayload;
import com.safedeal.global.response.CursorResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FavoriteQueryService {

    /** 커서에 실어 정렬을 바꾼 채 옛 커서를 재사용하는 것을 구분한다. */
    public static final String SORT_KEY = "favoritedAt,desc";
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    private final ListingFavoriteRepository listingFavoriteRepository;
    private final CursorCodec cursorCodec;

    public CursorResponse<FavoriteSummaryResponse> getMyFavorites(
            Long userId, String cursor, Integer size) {

        int pageSize = resolveSize(size);

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

        List<ListingFavorite> rows = listingFavoriteRepository.findPage(
                userId, lastCreatedAt, lastId, PageRequest.of(0, pageSize + 1));

        boolean hasNext = rows.size() > pageSize;
        List<ListingFavorite> page = hasNext ? rows.subList(0, pageSize) : rows;

        List<FavoriteSummaryResponse> items = new ArrayList<>(page.size());
        for (ListingFavorite row : page) {
            items.add(FavoriteSummaryResponse.from(row));
        }

        String nextCursor = null;
        if (hasNext) {
            ListingFavorite last = page.get(page.size() - 1);
            nextCursor = cursorCodec.encode(new CursorPayload(
                    Base64CursorCodec.VERSION, SORT_KEY,
                    last.getCreatedAt(), last.getId(), null, Instant.now()));
        }
        return new CursorResponse<>(items, nextCursor, hasNext);
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
}
