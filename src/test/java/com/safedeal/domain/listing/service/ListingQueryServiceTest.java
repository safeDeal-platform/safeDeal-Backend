package com.safedeal.domain.listing.service;

import com.safedeal.domain.listing.dto.ListingSummaryResponse;
import com.safedeal.domain.listing.entity.Category;
import com.safedeal.domain.listing.entity.ItemCondition;
import com.safedeal.domain.listing.entity.Listing;
import com.safedeal.domain.listing.entity.ListingStatus;
import com.safedeal.domain.listing.repository.CategoryRepository;
import com.safedeal.domain.listing.repository.ListingRepository;
import com.safedeal.domain.listing.repository.ListingSearchCondition;
import com.safedeal.global.exception.BusinessException;
import com.safedeal.global.response.Base64CursorCodec;
import com.safedeal.global.response.CursorCodec;
import com.safedeal.global.response.CursorPayload;
import com.safedeal.global.response.CursorResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 목록 조회의 분기를 컨테이너 없이 고정한다.
 *
 * <p>여기 있는 것들은 전부 <b>리팩토링 중에 조용히 되돌리기 쉬운</b> 판단이다. 특히
 * {@code resolveCategoryIds}의 {@code -1L} 센티널은 "빈 리스트를 주는 게 자연스럽다"고
 * 바꾸는 순간 카테고리 조건이 통째로 빠져 전체 매물이 나간다. 응답만 봐서는 드러나지 않으므로
 * 리포지토리에 실제로 넘어간 조건을 붙잡아 확인한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ListingQueryServiceTest {

    @Mock ListingRepository listingRepository;
    @Mock CategoryRepository categoryRepository;
    @Mock CursorCodec cursorCodec;

    private ListingQueryService service() {
        return new ListingQueryService(listingRepository, categoryRepository, cursorCodec);
    }

    private Category leaf(long id) {
        Category root = Category.root("DIGITAL", "디지털기기", 1);
        Category leaf = Category.child("DIGITAL_PHONE", "스마트폰", root, 1);
        ReflectionTestUtils.setField(leaf, "id", id);
        return leaf;
    }

    private Category root(long id) {
        Category root = Category.root("DIGITAL", "디지털기기", 1);
        ReflectionTestUtils.setField(root, "id", id);
        return root;
    }

    private Listing listing(long id, String publicId) {
        Listing listing = Listing.register(publicId, 1L, "아이폰", "설명", 950_000,
                leaf(10L), ItemCondition.USED, "서울특별시", "강남구", false);
        ReflectionTestUtils.setField(listing, "id", id);
        ReflectionTestUtils.setField(listing, "createdAt", Instant.parse("2026-09-01T00:00:00Z"));
        return listing;
    }

    private List<Listing> listings(int count) {
        List<Listing> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(listing(i + 1, "01J0000000000000000000000" + i));
        }
        return rows;
    }

    private ListingSearchCondition captureCondition() {
        ArgumentCaptor<ListingSearchCondition> captor =
                ArgumentCaptor.forClass(ListingSearchCondition.class);
        verify(listingRepository).findPublicPage(captor.capture());
        return captor.getValue();
    }

    // ── size ──────────────────────────────────────────────

    @Test
    @DisplayName("size를 주지 않으면 기본값 20으로 조회한다")
    void usesDefaultSize() {
        when(listingRepository.findPublicPage(any())).thenReturn(List.of());

        service().getListings(null, null, null, null, null, null, null);

        assertThat(captureCondition().size()).isEqualTo(ListingQueryService.DEFAULT_SIZE);
    }

    @Test
    @DisplayName("size가 0 이하거나 상한을 넘으면 조용히 깎지 않고 400으로 거부한다")
    void rejectsSizeOutOfRange() {
        for (int bad : new int[]{0, -1, ListingQueryService.MAX_SIZE + 1}) {
            assertThatThrownBy(() ->
                    service().getListings(null, bad, null, null, null, null, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("size");
        }
        verify(listingRepository, never()).findPublicPage(any());
    }

    @Test
    @DisplayName("상한값 자체는 허용한다 — 경계에서 한 칸 어긋나면 클라이언트가 마지막 페이지를 못 받는다")
    void allowsMaxSize() {
        when(listingRepository.findPublicPage(any())).thenReturn(List.of());

        service().getListings(null, ListingQueryService.MAX_SIZE, null, null, null, null, null);

        assertThat(captureCondition().size()).isEqualTo(ListingQueryService.MAX_SIZE);
    }

    // ── 커서 ──────────────────────────────────────────────

    @Test
    @DisplayName("정렬이 다른 커서는 거부한다 — 정렬을 바꾼 채 옛 커서를 재사용하면 페이지가 겹치거나 빈다")
    void rejectsCursorFromAnotherSort() {
        when(cursorCodec.decode("cursor")).thenReturn(new CursorPayload(
                Base64CursorCodec.VERSION, "price,asc",
                Instant.parse("2026-09-01T00:00:00Z"), 5L, null, Instant.now()));

        assertThatThrownBy(() ->
                service().getListings("cursor", null, null, null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("커서");
        verify(listingRepository, never()).findPublicPage(any());
    }

    @Test
    @DisplayName("커서의 생성시각·id가 조회 조건으로 넘어간다")
    void passesCursorKeysetToRepository() {
        Instant last = Instant.parse("2026-09-01T00:00:00Z");
        when(cursorCodec.decode("cursor")).thenReturn(new CursorPayload(
                Base64CursorCodec.VERSION, ListingQueryService.SORT_KEY, last, 7L, null, Instant.now()));
        when(listingRepository.findPublicPage(any())).thenReturn(List.of());

        service().getListings("cursor", null, null, null, null, null, null);

        ListingSearchCondition condition = captureCondition();
        assertThat(condition.lastCreatedAt()).isEqualTo(last);
        assertThat(condition.lastId()).isEqualTo(7L);
    }

    // ── 가격 범위 ─────────────────────────────────────────

    @Test
    @DisplayName("최소 가격이 최대 가격보다 크면 400으로 거부한다")
    void rejectsInvertedPriceRange() {
        assertThatThrownBy(() ->
                service().getListings(null, null, null, null, null, 500_000, 100_000))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("가격");
        verify(listingRepository, never()).findPublicPage(any());
    }

    // ── 카테고리 펼치기 ───────────────────────────────────

    @Test
    @DisplayName("중분류로 거르면 그 id 하나만 넘긴다")
    void usesLeafCategoryIdDirectly() {
        when(categoryRepository.findByCode("DIGITAL_PHONE")).thenReturn(Optional.of(leaf(10L)));
        when(listingRepository.findPublicPage(any())).thenReturn(List.of());

        service().getListings(null, null, "DIGITAL_PHONE", null, null, null, null);

        assertThat(captureCondition().categoryIds()).containsExactly(10L);
    }

    @Test
    @DisplayName("중분류를 내리면 그 code로 직접 찍어도 매물이 안 나가고, 되살리면 다시 나간다")
    void leafCategoryFilterFollowsActiveFlag() {
        // 같은 엔티티 한 개의 active만 뒤집어 두 번 조회한다. 서로 다른 객체로 나눠 쓰면
        // "새로 만든 분류는 기본이 활성"이라는 것만 확인하게 되어(usesLeafCategoryIdDirectly와 중복)
        // 비활성 검사가 실제로 active 값을 보고 갈리는지는 검증되지 않는다.
        Category category = leaf(10L);
        when(categoryRepository.findByCode("DIGITAL_PHONE")).thenReturn(Optional.of(category));
        when(listingRepository.findPublicPage(any())).thenReturn(List.of());

        category.deactivate();
        service().getListings(null, null, "DIGITAL_PHONE", null, null, null, null);
        category.activate();
        service().getListings(null, null, "DIGITAL_PHONE", null, null, null, null);

        // captureCondition()은 호출 1회만 허용하므로 여기서는 두 번의 호출을 모두 붙잡는다.
        ArgumentCaptor<ListingSearchCondition> captor =
                ArgumentCaptor.forClass(ListingSearchCondition.class);
        verify(listingRepository, times(2)).findPublicPage(captor.capture());
        List<ListingSearchCondition> calls = captor.getAllValues();

        // 비활성일 때: 대분류로 걸렀을 때 비활성 자식이 빠지는 것과 결과가 같아야 한다. id를 그대로
        // 넘기면 예전 링크·북마크로 들어온 요청에만 내려간 분류가 계속 열린다.
        // 빈 목록이 아니라 -1L인 이유는 keepsFilterWhenRootHasNoActiveLeaf와 같다.
        assertThat(calls.get(0).categoryIds()).isNotEmpty().containsExactly(-1L);
        // 되살리면 같은 분류가 다시 나간다 — 비활성 검사가 활성 경로까지 막아버리지 않는다.
        assertThat(calls.get(1).categoryIds()).containsExactly(10L);
    }

    @Test
    @DisplayName("대분류로 거르면 하위 중분류로 펼쳐서 넘긴다 — 매물은 중분류에만 달린다")
    void expandsRootCategoryToItsLeaves() {
        Category digital = root(1L);
        when(categoryRepository.findByCode("DIGITAL")).thenReturn(Optional.of(digital));
        when(categoryRepository.findByParentIdAndActiveTrue(1L))
                .thenReturn(List.of(leaf(10L), leaf(11L)));
        when(listingRepository.findPublicPage(any())).thenReturn(List.of());

        service().getListings(null, null, "DIGITAL", null, null, null, null);

        assertThat(captureCondition().categoryIds()).containsExactly(10L, 11L);
    }

    @Test
    @DisplayName("활성 중분류가 없는 대분류는 빈 목록이 아니라 존재할 수 없는 id를 넘긴다")
    void keepsFilterWhenRootHasNoActiveLeaf() {
        when(categoryRepository.findByCode("DIGITAL")).thenReturn(Optional.of(root(1L)));
        when(categoryRepository.findByParentIdAndActiveTrue(1L)).thenReturn(List.of());
        when(listingRepository.findPublicPage(any())).thenReturn(List.of());

        service().getListings(null, null, "DIGITAL", null, null, null, null);

        // 빈 목록을 넘기면 리포지토리가 카테고리 조건 자체를 빼서 전체 매물이 나간다.
        // "결과 없음"과 "조건 없음"은 다르다.
        assertThat(captureCondition().categoryIds()).isNotEmpty().containsExactly(-1L);
    }

    @Test
    @DisplayName("없는 카테고리 code로 거르면 400으로 거부한다")
    void rejectsUnknownCategoryCode() {
        when(categoryRepository.findByCode("NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                service().getListings(null, null, "NOPE", null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("카테고리");
        verify(listingRepository, never()).findPublicPage(any());
    }

    // ── hasNext 판정과 절단 ───────────────────────────────

    @Test
    @DisplayName("size보다 한 건 더 오면 잘라내고 다음 커서를 발급한다")
    void trimsExtraRowAndIssuesCursor() {
        when(listingRepository.findPublicPage(any())).thenReturn(listings(4));
        when(cursorCodec.encode(any())).thenReturn("next");

        CursorResponse<ListingSummaryResponse> response =
                service().getListings(null, 3, null, null, null, null, null);

        assertThat(response.items()).hasSize(3);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.nextCursor()).isEqualTo("next");
    }

    @Test
    @DisplayName("정확히 size만큼 오면 다음이 없다고 보고 커서를 발급하지 않는다")
    void reportsNoNextWhenPageIsExactlyFull() {
        when(listingRepository.findPublicPage(any())).thenReturn(listings(3));

        CursorResponse<ListingSummaryResponse> response =
                service().getListings(null, 3, null, null, null, null, null);

        assertThat(response.items()).hasSize(3);
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
        verify(cursorCodec, never()).encode(any());
    }

    @Test
    @DisplayName("다음 커서는 잘라낸 뒤 마지막 항목을 기준으로 만든다 — 더 읽어온 한 건이 기준이 되면 한 건이 건너뛰어진다")
    void cursorPointsAtLastItemOfTrimmedPage() {
        when(listingRepository.findPublicPage(any())).thenReturn(listings(4));
        when(cursorCodec.encode(any())).thenReturn("next");

        service().getListings(null, 3, null, null, null, null, null);

        ArgumentCaptor<CursorPayload> captor = ArgumentCaptor.forClass(CursorPayload.class);
        verify(cursorCodec).encode(captor.capture());
        assertThat(captor.getValue().lastId()).isEqualTo(3L);
        assertThat(captor.getValue().sort()).isEqualTo(ListingQueryService.SORT_KEY);
    }

    // ── 상세 조회 ─────────────────────────────────────────

    @Test
    @DisplayName("카테고리가 내려가도 상세는 열어준다 — 목록에서 빠지는 것과 판단이 다른 것이 의도다")
    void detailStaysOpenWhenCategoryIsDeactivated() {
        // 목록(resolveCategoryIds)은 비활성 분류를 걸러내지만 상세는 막지 않는다. 내려간 것은
        // 분류일 뿐 매물은 판매중이고, 여기서 404를 주면 채팅으로 흥정하던 구매자와 판매자 본인이
        // 자기 매물을 못 본다. 이 판단을 "일관성"을 이유로 되돌리면 이 테스트가 깨진다.
        Category retired = leaf(10L);
        retired.deactivate();
        Listing listing = Listing.register("01J00000000000000000000001", 1L, "아이폰", "설명",
                950_000, retired, ItemCondition.USED, "서울특별시", "강남구", false);
        when(listingRepository.findByPublicIdAndDeletedAtIsNull("01J00000000000000000000001"))
                .thenReturn(Optional.of(listing));

        assertThat(service().getListing("01J00000000000000000000001")).isNotNull();
    }

    @Test
    @DisplayName("차단된 매물은 존재 자체를 알리지 않는다 — 404")
    void blockedListingIsNotFound() {
        Listing blocked = listing(1L, "01J00000000000000000000002");
        ReflectionTestUtils.setField(blocked, "status", ListingStatus.BLOCKED);
        when(listingRepository.findByPublicIdAndDeletedAtIsNull("01J00000000000000000000002"))
                .thenReturn(Optional.of(blocked));

        assertThatThrownBy(() -> service().getListing("01J00000000000000000000002"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("매물");
    }
}
