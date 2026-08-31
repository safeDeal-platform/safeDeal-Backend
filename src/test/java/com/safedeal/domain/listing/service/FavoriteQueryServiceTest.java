package com.safedeal.domain.listing.service;

import com.safedeal.domain.listing.dto.FavoriteSummaryResponse;
import com.safedeal.domain.listing.entity.Category;
import com.safedeal.domain.listing.entity.ItemCondition;
import com.safedeal.domain.listing.entity.Listing;
import com.safedeal.domain.listing.entity.ListingFavorite;
import com.safedeal.domain.listing.repository.ListingFavoriteRepository;
import com.safedeal.global.exception.BusinessException;
import com.safedeal.global.response.Base64CursorCodec;
import com.safedeal.global.response.CursorPayload;
import com.safedeal.global.response.CursorResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FavoriteQueryServiceTest {

    private static final Long USER = 7L;

    private final ListingFavoriteRepository repository = mock(ListingFavoriteRepository.class);
    private final Base64CursorCodec cursorCodec =
            new Base64CursorCodec(new ObjectMapper());

    private FavoriteQueryService service;

    @BeforeEach
    void setUp() {
        service = new FavoriteQueryService(repository, cursorCodec);
    }

    private ListingFavorite favorite(long id, Instant createdAt) {
        Category root = Category.root("DIGITAL", "디지털기기", 1);
        Category leaf = Category.child("DIGITAL_PHONE", "스마트폰", root, 1);
        Listing listing = Listing.register("01J3A" + id, 1L, "아이폰 " + id, "설명", 950_000,
                leaf, ItemCondition.USED, "서울특별시", "강남구", false);
        ListingFavorite favorite = ListingFavorite.of(USER, listing);
        ReflectionTestUtils.setField(favorite, "id", id);
        ReflectionTestUtils.setField(favorite, "createdAt", createdAt);
        return favorite;
    }

    private List<ListingFavorite> rows(int count) {
        List<ListingFavorite> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(favorite(100 - i, Instant.parse("2026-08-30T00:00:00Z").minusSeconds(i)));
        }
        return rows;
    }

    @Test
    @DisplayName("size+1건이 오면 초과분을 잘라내고 hasNext를 켠다")
    void trimsExtraRowAndFlagsHasNext() {
        when(repository.findPage(eq(USER), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(rows(3));

        CursorResponse<FavoriteSummaryResponse> response = service.getMyFavorites(USER, null, 2);

        assertThat(response.items()).hasSize(2);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.nextCursor()).isNotNull();
    }

    @Test
    @DisplayName("마지막 페이지면 커서를 주지 않는다")
    void noCursorOnLastPage() {
        when(repository.findPage(eq(USER), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(rows(2));

        CursorResponse<FavoriteSummaryResponse> response = service.getMyFavorites(USER, null, 2);

        assertThat(response.items()).hasSize(2);
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    @DisplayName("발급한 커서는 마지막 항목의 created_at·id를 가리킨다")
    void cursorPointsAtLastItem() {
        List<ListingFavorite> page = rows(3);
        when(repository.findPage(eq(USER), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(page);

        String cursor = service.getMyFavorites(USER, null, 2).nextCursor();
        CursorPayload payload = cursorCodec.decode(cursor);

        assertThat(payload.lastId()).isEqualTo(page.get(1).getId());
        assertThat(payload.lastCreatedAt()).isEqualTo(page.get(1).getCreatedAt());
        assertThat(payload.sort()).isEqualTo(FavoriteQueryService.SORT_KEY);
    }

    @Test
    @DisplayName("받은 커서의 위치를 그대로 조회 조건으로 넘긴다")
    void passesCursorPositionToRepository() {
        Instant at = Instant.parse("2026-08-30T00:00:00Z");
        String cursor = cursorCodec.encode(new CursorPayload(
                Base64CursorCodec.VERSION, FavoriteQueryService.SORT_KEY, at, 42L, null, at));
        when(repository.findPage(eq(USER), eq(at), eq(42L), any(Pageable.class)))
                .thenReturn(List.of());

        service.getMyFavorites(USER, cursor, 2);

        // 이 검증이 없으면 서비스가 커서를 디코딩만 하고 버려도 스위트가 전부 통과한다 —
        // 무한 스크롤이 첫 페이지만 반복하는 버그가 그대로 지나간다.
        verify(repository).findPage(eq(USER), eq(at), eq(42L), any(Pageable.class));
    }

    @Test
    @DisplayName("다른 정렬로 발급된 커서는 거부한다")
    void rejectsCursorFromAnotherSort() {
        String foreign = cursorCodec.encode(new CursorPayload(
                Base64CursorCodec.VERSION, "createdAt,desc",
                Instant.parse("2026-08-30T00:00:00Z"), 1L, null, Instant.parse("2026-08-30T00:00:00Z")));

        assertThatThrownBy(() -> service.getMyFavorites(USER, foreign, 2))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("size가 없으면 기본값, 상한을 넘으면 거부한다")
    void validatesSize() {
        when(repository.findPage(eq(USER), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(List.of());

        assertThat(service.getMyFavorites(USER, null, null).items()).isEmpty();
        assertThatThrownBy(() -> service.getMyFavorites(USER, null, 0))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.getMyFavorites(USER, null, FavoriteQueryService.MAX_SIZE + 1))
                .isInstanceOf(BusinessException.class);
    }
}
