package com.safedeal.domain.listing.repository;

import com.safedeal.domain.listing.entity.Category;
import com.safedeal.domain.listing.entity.ItemCondition;
import com.safedeal.domain.listing.entity.Listing;
import com.safedeal.domain.listing.entity.ListingStatus;
import com.safedeal.domain.listing.entity.SoldSource;
import com.safedeal.testsupport.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 조건부 UPDATE가 동시 전이를 실제로 한 쪽만 통과시키는지 확인한다.
 *
 * <p><b>{@code @Transactional}을 붙이지 않는다.</b> 별도 스레드는 테스트 트랜잭션 밖이라 롤백
 * 대상이 아니고, 롤백 안에 갇히면 다른 스레드가 그 변경을 보지 못해 경쟁 자체가 재현되지
 * 않는다. 대신 {@code @AfterEach}에서 직접 지운다.
 */
class ListingStatusTransitionTest extends IntegrationTestSupport {

    private static final long SELLER_ID = 1L;

    @Autowired
    ListingRepository listingRepository;

    @Autowired
    CategoryRepository categoryRepository;

    private Category phone;

    @BeforeEach
    void setUp() {
        phone = categoryRepository.findByCode("DIGITAL_PHONE").orElseThrow();
        listingRepository.deleteAllInBatch();
    }

    @AfterEach
    void clear() {
        listingRepository.deleteAllInBatch();
    }

    private Listing saveActive(String publicId) {
        return listingRepository.saveAndFlush(Listing.register(publicId, SELLER_ID,
                "아이폰", "설명", 950_000, phone, ItemCondition.USED,
                "서울특별시", "강남구", false));
    }

    @Test
    @DisplayName("동시에 판매완료를 눌러도 한 번만 성공한다")
    void concurrentMarkSoldSucceedsOnce() throws Exception {
        Listing listing = saveActive("01M00000000000000000000001");
        int threads = 8;

        // ExecutorService는 Java 19부터 AutoCloseable이라 여기서는 직접 종료한다.
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Integer>> tasks = java.util.Collections.nCopies(threads,
                    () -> listingRepository.markSoldByOwner(
                            listing.getId(), SELLER_ID, Instant.now()));
            int succeeded = 0;
            for (Future<Integer> f : pool.invokeAll(tasks)) {
                succeeded += f.get();
            }
            // 전이표는 각 서버 메모리에서만 검증하므로 여기서 여러 건이 통과할 수 있다.
            // WHERE에 기대 상태를 실은 조건부 UPDATE가 DB에서 한 건만 성공시킨다.
            assertThat(succeeded).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        Listing reloaded = listingRepository.findByPublicId(listing.getPublicId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ListingStatus.SOLD);
        assertThat(reloaded.getSoldSource()).isEqualTo(SoldSource.MANUAL);
    }

    @Test
    @DisplayName("남의 매물은 판매완료로 바꿀 수 없다")
    void otherUserCannotMarkSold() {
        Listing listing = saveActive("01M00000000000000000000002");

        int affected = listingRepository.markSoldByOwner(listing.getId(), 999L, Instant.now());

        assertThat(affected).isZero();
    }

    @Test
    @DisplayName("24시간 안이면 수동 판매완료를 되돌린다")
    void restoreWithinWindow() {
        Listing listing = saveActive("01M00000000000000000000003");
        listingRepository.markSoldByOwner(listing.getId(), SELLER_ID, Instant.now());

        int affected = listingRepository.restoreManualSoldByOwner(
                listing.getId(), SELLER_ID, Instant.now().minus(Duration.ofHours(24)));

        assertThat(affected).isEqualTo(1);
        Listing reloaded = listingRepository.findByPublicId(listing.getPublicId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ListingStatus.ACTIVE);
        assertThat(reloaded.getSoldSource()).isNull();
        assertThat(reloaded.getSoldAt()).isNull();
    }

    @Test
    @DisplayName("24시간이 지나면 되돌릴 수 없다")
    void restoreAfterWindowFails() {
        Listing listing = saveActive("01M00000000000000000000004");
        listingRepository.markSoldByOwner(
                listing.getId(), SELLER_ID, Instant.now().minus(Duration.ofHours(25)));

        int affected = listingRepository.restoreManualSoldByOwner(
                listing.getId(), SELLER_ID, Instant.now().minus(Duration.ofHours(24)));

        assertThat(affected).isZero();
    }

    @Test
    @DisplayName("수정하면 version이 올라간다 - 클라이언트가 다음 수정에 쓸 값")
    void versionIncrementsOnUpdate() {
        Listing listing = saveActive("01M00000000000000000000006");
        Long before = listing.getVersion();

        listing.update("수정", "설명", 800_000, phone, ItemCondition.USED,
                "서울특별시", "강남구", java.time.LocalDate.of(2026, 8, 31));
        // 트랜잭션 밖이라 listing은 분리 상태다. merge 결과가 최신 버전을 갖는다.
        Listing merged = listingRepository.saveAndFlush(listing);

        assertThat(merged.getVersion()).isGreaterThan(before);
    }

    @Test
    @DisplayName("조회수는 오르지만 version은 그대로다 - 판매자 수정이 깨지지 않아야 한다")
    void viewCountDoesNotBumpVersion() {
        Listing listing = saveActive("01M00000000000000000000007");
        Long versionBefore = listing.getVersion();

        listingRepository.increaseViewCount(listing.getPublicId(), 999L);

        Listing reloaded = listingRepository.findByPublicId(listing.getPublicId()).orElseThrow();
        assertThat(reloaded.getViewCount()).isEqualTo(1);
        assertThat(reloaded.getVersion()).isEqualTo(versionBefore);
    }

    @Test
    @DisplayName("판매자 본인이 열어보면 조회수가 오르지 않는다")
    void ownerViewIsNotCounted() {
        Listing listing = saveActive("01M00000000000000000000008");

        int affected = listingRepository.increaseViewCount(listing.getPublicId(), SELLER_ID);

        assertThat(affected).isZero();
        assertThat(listingRepository.findByPublicId(listing.getPublicId()).orElseThrow()
                .getViewCount()).isZero();
    }

    @Test
    @DisplayName("비로그인 조회도 조회수에 센다")
    void anonymousViewIsCounted() {
        Listing listing = saveActive("01M00000000000000000000009");

        listingRepository.increaseViewCount(listing.getPublicId(), null);

        assertThat(listingRepository.findByPublicId(listing.getPublicId()).orElseThrow()
                .getViewCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("삭제된 매물은 조회수가 오르지 않는다")
    void deletedListingNotCounted() {
        Listing listing = saveActive("01M00000000000000000000010");
        listing.softDelete(Instant.now());
        listingRepository.saveAndFlush(listing);

        int affected = listingRepository.increaseViewCount(listing.getPublicId(), 999L);

        assertThat(affected).isZero();
    }

    @Test
    @DisplayName("공개 상태가 아니면 판매완료로 바꿀 수 없다")
    void nonActiveCannotBeMarkedSold() {
        Listing listing = saveActive("01M00000000000000000000005");
        listingRepository.markSoldByOwner(listing.getId(), SELLER_ID, Instant.now());

        int affected = listingRepository.markSoldByOwner(
                listing.getId(), SELLER_ID, Instant.now());

        assertThat(affected).isZero();
    }
}
