package com.safedeal.domain.listing.repository;

import com.safedeal.domain.listing.entity.Category;
import com.safedeal.domain.listing.entity.ItemCondition;
import com.safedeal.domain.listing.entity.Listing;
import com.safedeal.domain.listing.entity.ListingFavorite;
import com.safedeal.domain.listing.entity.ListingStatus;
import com.safedeal.testsupport.IntegrationTestSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UNIQUE 제약과 커서 조건은 실제 DB에서만 증명된다. 특히 "팔린 매물도 목록에 남는다"는
 * 조인 조건에 상태 필터를 넣는 순간 조용히 깨지므로 여기서 고정한다.
 */
@Transactional
class ListingFavoriteRepositoryTest extends IntegrationTestSupport {

    @Autowired ListingFavoriteRepository listingFavoriteRepository;
    @Autowired ListingRepository listingRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired EntityManager entityManager;

    private static final Long USER = 100L;

    private Category phone;

    @BeforeEach
    void setUp() {
        phone = categoryRepository.findByCode("DIGITAL_PHONE").orElseThrow();
        listingFavoriteRepository.deleteAllInBatch();
        listingRepository.deleteAllInBatch();
    }

    private Listing listing(String title) {
        Listing listing = Listing.register(
                "01J" + String.format("%023d", Math.abs(title.hashCode() % 1_000_000)),
                1L, title, "설명", 950_000, phone, ItemCondition.USED,
                "서울특별시", "강남구", false);
        return listingRepository.saveAndFlush(listing);
    }

    private ListingFavorite favorite(Long userId, Listing listing) {
        return listingFavoriteRepository.saveAndFlush(
                ListingFavorite.of(userId, listing, listing.getPrice()));
    }

    private List<ListingFavorite> page(Instant lastCreatedAt, Long lastId, int size) {
        return listingFavoriteRepository.findPage(
                USER, lastCreatedAt, lastId, PageRequest.of(0, size));
    }

    @Test
    @DisplayName("같은 사용자가 같은 매물을 두 번 찜할 수 없다")
    void rejectsDuplicateFavorite() {
        Listing listing = listing("아이폰");
        favorite(USER, listing);

        assertThatThrownBy(() -> favorite(USER, listing))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("insertIfAbsent를 두 번 호출해도 행은 하나이고 기준가는 최초 값이 남는다")
    void insertIfAbsentIsIdempotent() {
        Listing listing = listing("아이폰");
        Instant now = Instant.parse("2026-09-01T00:00:00Z");

        listingFavoriteRepository.insertIfAbsent(USER, listing.getId(), 950_000, now);
        // 판매자가 가격을 올린 뒤 사용자가 하트를 다시 누른 상황
        listingFavoriteRepository.insertIfAbsent(USER, listing.getId(), 1_200_000, now);
        entityManager.clear();

        assertThat(listingFavoriteRepository.count()).isEqualTo(1);
        assertThat(listingFavoriteRepository.findByUserIdAndListing(USER, listing).orElseThrow()
                .getNotifyBasePrice()).isEqualTo(950_000);
    }

    @Test
    @DisplayName("deleteByUserIdAndListingId는 없는 찜에 대해 0행을 돌려준다")
    void deleteReturnsZeroWhenAbsent() {
        Listing listing = listing("아이폰");

        assertThat(listingFavoriteRepository.deleteByUserIdAndListingId(USER, listing.getId()))
                .isZero();

        favorite(USER, listing);
        assertThat(listingFavoriteRepository.deleteByUserIdAndListingId(USER, listing.getId()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("다른 사용자는 같은 매물을 각자 찜할 수 있다")
    void allowsSameListingForDifferentUsers() {
        Listing listing = listing("아이폰");
        favorite(USER, listing);
        favorite(200L, listing);

        assertThat(listingFavoriteRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("찜한 시각 역순으로 나오고 요청 size보다 1건 많게 돌려준다")
    void returnsNewestFirstWithOneExtra() {
        favorite(USER, listing("A"));
        favorite(USER, listing("B"));
        favorite(USER, listing("C"));
        favorite(USER, listing("D"));

        // 서비스는 size=3이면 size+1=4로 조회해 초과분 유무로 hasNext를 판정한다.
        List<ListingFavorite> rows = page(null, null, 4);

        assertThat(rows).hasSize(4);
        assertThat(rows).extracting(f -> f.getListing().getTitle())
                .containsExactly("D", "C", "B", "A");
    }

    @Test
    @DisplayName("커서로 이어 받으면 건너뜀·중복이 없다")
    void pagesWithoutGapOrDuplicate() {
        favorite(USER, listing("A"));
        favorite(USER, listing("B"));
        favorite(USER, listing("C"));

        List<ListingFavorite> first = page(null, null, 3);
        ListingFavorite last = first.get(1);
        List<ListingFavorite> second = page(last.getCreatedAt(), last.getId(), 3);

        assertThat(first.subList(0, 2)).extracting(f -> f.getListing().getTitle())
                .containsExactly("C", "B");
        assertThat(second).extracting(f -> f.getListing().getTitle())
                .containsExactly("A");
    }

    @Test
    @DisplayName("같은 시각에 찜한 행은 id 역순으로 갈라진다")
    void breaksTieById() {
        Listing a = listing("A");
        Listing b = listing("B");
        Instant sameMoment = Instant.parse("2026-09-01T00:00:00Z");
        ListingFavorite first = favorite(USER, a);
        ListingFavorite second = favorite(USER, b);
        entityManager.createQuery("UPDATE ListingFavorite f SET f.createdAt = :t")
                .setParameter("t", sameMoment).executeUpdate();
        entityManager.clear();

        List<ListingFavorite> rows = page(null, null, 10);

        assertThat(rows).extracting(ListingFavorite::getId)
                .containsExactly(second.getId(), first.getId());
    }

    @Test
    @DisplayName("동일 시각 행이 페이지 경계에 걸려도 건너뜀·중복이 없다")
    void pagesAcrossTieAtBoundary() {
        Listing a = listing("A");
        Listing b = listing("B");
        Instant sameMoment = Instant.parse("2026-09-01T00:00:00Z");
        ListingFavorite first = favorite(USER, a);
        ListingFavorite second = favorite(USER, b);
        entityManager.createQuery("UPDATE ListingFavorite f SET f.createdAt = :t")
                .setParameter("t", sameMoment).executeUpdate();
        entityManager.clear();

        // 앞 페이지가 second에서 끊긴 상황. created_at이 같으므로 id 비교 분기로만 이어진다 —
        // 찜 연속 클릭이면 같은 초에 여러 건이 쌓여 흔하게 발생한다.
        List<ListingFavorite> next = page(sameMoment, second.getId(), 10);

        assertThat(next).extracting(ListingFavorite::getId).containsExactly(first.getId());
    }

    @Test
    @DisplayName("팔리거나 삭제된 매물의 찜도 목록에 남는다")
    void keepsFavoritesOfUnavailableListings() {
        Listing sold = listing("팔린 매물");
        Listing deleted = listing("삭제된 매물");
        favorite(USER, sold);
        favorite(USER, deleted);

        listingRepository.markSoldByOwner(sold.getId(), 1L, Instant.now());
        deleted.softDelete(Instant.now());
        listingRepository.saveAndFlush(deleted);
        entityManager.clear();

        List<ListingFavorite> rows = page(null, null, 10);

        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(f -> f.getListing().getStatus())
                .containsExactlyInAnyOrder(ListingStatus.SOLD, ListingStatus.DELETED);
    }

    @Test
    @DisplayName("남의 찜은 섞이지 않는다")
    void isolatesByUser() {
        favorite(USER, listing("내 것"));
        favorite(200L, listing("남의 것"));

        assertThat(page(null, null, 10)).extracting(f -> f.getListing().getTitle())
                .containsExactly("내 것");
    }
}
