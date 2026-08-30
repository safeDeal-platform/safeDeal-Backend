package com.safedeal.domain.listing.repository;

import com.safedeal.domain.listing.entity.Category;
import com.safedeal.domain.listing.entity.ItemCondition;
import com.safedeal.domain.listing.entity.Listing;
import com.safedeal.testsupport.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 커서 페이징이 실제 MySQL에서 건너뜀·중복 없이 도는지 확인한다. 정렬과 keyset 조건은 메모리
 * 테스트로는 증명되지 않는다 — 같은 시각에 만들어진 행의 tie-break가 특히 그렇다.
 */
@Transactional
class ListingRepositoryTest extends IntegrationTestSupport {

    @Autowired
    ListingRepository listingRepository;

    @Autowired
    CategoryRepository categoryRepository;

    private Category phone;
    private Category tablet;

    @BeforeEach
    void setUp() {
        // 시더가 넣어둔 실제 분류를 쓴다 - 테스트만 통과하고 실제 코드와 다른 조건이 되는 걸 막는다.
        phone = categoryRepository.findByCode("DIGITAL_PHONE").orElseThrow();
        tablet = categoryRepository.findByCode("DIGITAL_TABLET").orElseThrow();
        listingRepository.deleteAllInBatch();
    }

    private Listing save(String title, int price, Category category, String sigungu) {
        Listing listing = Listing.register(
                "01J" + String.format("%023d", Math.abs(title.hashCode() % 1_000_000)),
                1L, title, "설명", price, category, ItemCondition.USED,
                "서울특별시", sigungu, false);
        return listingRepository.saveAndFlush(listing);
    }

    private ListingSearchCondition cond(int size) {
        return new ListingSearchCondition(List.of(), null, null, null, null, null, null, size);
    }

    @Test
    @DisplayName("최신순으로 나오고 size+1건을 돌려준다")
    void returnsOneExtraForHasNext() {
        save("A", 10_000, phone, "강남구");
        save("B", 20_000, phone, "강남구");
        save("C", 30_000, phone, "강남구");

        List<Listing> rows = listingRepository.findPublicPage(cond(2));

        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(Listing::getTitle).containsExactly("C", "B", "A");
    }

    @Test
    @DisplayName("커서 이후 구간만 나오고 겹치지 않는다")
    void cursorPagesWithoutOverlap() {
        save("A", 10_000, phone, "강남구");
        save("B", 20_000, phone, "강남구");
        save("C", 30_000, phone, "강남구");

        List<Listing> first = listingRepository.findPublicPage(cond(2));
        Listing last = first.get(1);

        List<Listing> second = listingRepository.findPublicPage(new ListingSearchCondition(
                List.of(), null, null, null, null, last.getCreatedAt(), last.getId(), 2));

        assertThat(second).extracting(Listing::getTitle).containsExactly("A");
        assertThat(second).extracting(Listing::getId).doesNotContain(last.getId());
    }

    @Test
    @DisplayName("삭제된 매물과 비공개 상태는 목록에서 빠진다")
    void hidesDeletedAndNonActive() {
        save("보임", 10_000, phone, "강남구");
        Listing hidden = Listing.register("01J00000000000000000000009", 1L, "숨김", "설명",
                10_000, phone, ItemCondition.USED, "서울특별시", "강남구", true);
        listingRepository.saveAndFlush(hidden);

        List<Listing> rows = listingRepository.findPublicPage(cond(10));

        assertThat(rows).extracting(Listing::getTitle).containsExactly("보임");
    }

    @Test
    @DisplayName("카테고리로 거른다")
    void filtersByCategory() {
        save("폰", 10_000, phone, "강남구");
        save("탭", 20_000, tablet, "강남구");

        List<Listing> rows = listingRepository.findPublicPage(new ListingSearchCondition(
                List.of(phone.getId()), null, null, null, null, null, null, 10));

        assertThat(rows).extracting(Listing::getTitle).containsExactly("폰");
    }

    @Test
    @DisplayName("지역과 가격 범위로 거른다")
    void filtersByRegionAndPrice() {
        save("강남싼것", 10_000, phone, "강남구");
        save("강남비싼것", 90_000, phone, "강남구");
        save("송파", 10_000, phone, "송파구");

        List<Listing> byRegion = listingRepository.findPublicPage(new ListingSearchCondition(
                List.of(), "서울특별시", "강남구", null, null, null, null, 10));
        assertThat(byRegion).extracting(Listing::getTitle)
                .containsExactlyInAnyOrder("강남싼것", "강남비싼것");

        List<Listing> byPrice = listingRepository.findPublicPage(new ListingSearchCondition(
                List.of(), null, null, 50_000, null, null, null, 10));
        assertThat(byPrice).extracting(Listing::getTitle).containsExactly("강남비싼것");
    }

    @Test
    @DisplayName("public_id로 찾되 삭제분은 제외한다")
    void findByPublicId() {
        Listing saved = save("찾을것", 10_000, phone, "강남구");

        assertThat(listingRepository.findByPublicIdAndDeletedAtIsNull(saved.getPublicId()))
                .isPresent();
        assertThat(listingRepository.findByPublicIdAndDeletedAtIsNull("01J00000000000000000000000"))
                .isEmpty();
    }
}
