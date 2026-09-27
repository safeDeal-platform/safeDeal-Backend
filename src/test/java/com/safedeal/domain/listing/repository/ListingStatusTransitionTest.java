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
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcTemplate jdbc;

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

    // ── 수정·삭제(엔티티 저장)와 벌크 전이의 경합 ─────────────────────────────

    /** 트랜잭션이 열려 있는 동안 다른 커넥션에서 실행한다. 같은 스레드면 같은 트랜잭션에 묶인다. */
    private void inOtherThread(Runnable task) {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(task).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("수정하는 사이 판매완료가 커밋되면 수정이 실패하고 판매완료가 유지된다")
    void updateDoesNotOverwriteConcurrentMarkSold() {
        Listing listing = saveActive("01M00000000000000000000011");

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            // 서비스 update()와 같은 순서: 읽고(ACTIVE) → 다른 요청이 끼어들고 → 고치고 flush.
            Listing loaded = listingRepository.findByPublicId(listing.getPublicId()).orElseThrow();
            inOtherThread(() -> listingRepository.markSoldByOwner(
                    listing.getId(), SELLER_ID, Instant.now()));
            loaded.update("수정", "설명", 950_000, phone, ItemCondition.USED,
                    "서울특별시", "강남구", LocalDate.of(2026, 9, 20));
            listingRepository.flush();
        })).isInstanceOf(OptimisticLockingFailureException.class);

        Listing reloaded = listingRepository.findByPublicId(listing.getPublicId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ListingStatus.SOLD);
        assertThat(reloaded.getSoldSource()).isEqualTo(SoldSource.MANUAL);
        assertThat(reloaded.getSoldAt()).isNotNull();
    }

    @Test
    @DisplayName("수정하는 사이 오른 조회수를 수정이 옛 값으로 되돌리지 않는다")
    void updateDoesNotRollBackConcurrentViewCount() {
        Listing listing = saveActive("01M00000000000000000000012");

        tx.executeWithoutResult(status -> {
            Listing loaded = listingRepository.findByPublicId(listing.getPublicId()).orElseThrow();
            inOtherThread(() -> listingRepository.increaseViewCount(listing.getPublicId(), 999L));
            loaded.update("수정", "설명", 950_000, phone, ItemCondition.USED,
                    "서울특별시", "강남구", LocalDate.of(2026, 9, 20));
            listingRepository.flush();
        });

        // 조회수는 version을 올리지 않으므로 낙관적 락이 막지 못한다. 바뀐 컬럼만 쓰는지가 유일한 방어다.
        Listing reloaded = listingRepository.findByPublicId(listing.getPublicId()).orElseThrow();
        assertThat(reloaded.getViewCount()).isEqualTo(1);
        assertThat(reloaded.getTitle()).isEqualTo("수정");
    }

    // ── 되돌리기 가드 · 삭제 조건부 UPDATE · 버전 ─────────────────────────────

    /** 결제로 팔린 상태를 만든다. 결제 도메인이 아직 없어 엔티티로는 만들 수 없다. */
    private void markPaymentSold(Listing listing) {
        jdbc.update("UPDATE listings SET status = 'SOLD', sold_source = 'PAYMENT', "
                        + "sold_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now()), listing.getId());
    }

    private Instant restoreThreshold() {
        return Instant.now().minus(Duration.ofHours(24));
    }

    @Test
    @DisplayName("결제로 팔린 매물은 24시간 안이어도 되돌릴 수 없다 - 통계 정합")
    void paymentSoldCannotBeRestored() {
        Listing listing = saveActive("01M00000000000000000000013");
        markPaymentSold(listing);

        int affected = listingRepository.restoreManualSoldByOwner(
                listing.getId(), SELLER_ID, restoreThreshold());

        assertThat(affected).isZero();
        Listing reloaded = listingRepository.findByPublicId(listing.getPublicId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ListingStatus.SOLD);
        assertThat(reloaded.getSoldSource()).isEqualTo(SoldSource.PAYMENT);
    }

    @Test
    @DisplayName("남의 매물은 되돌릴 수 없다")
    void otherUserCannotRestore() {
        Listing listing = saveActive("01M00000000000000000000014");
        listingRepository.markSoldByOwner(listing.getId(), SELLER_ID, Instant.now());

        int affected = listingRepository.restoreManualSoldByOwner(
                listing.getId(), 999L, restoreThreshold());

        assertThat(affected).isZero();
        assertThat(listingRepository.findByPublicId(listing.getPublicId()).orElseThrow()
                .getStatus()).isEqualTo(ListingStatus.SOLD);
    }

    @Test
    @DisplayName("판매완료가 아닌 매물은 되돌릴 게 없다")
    void activeCannotBeRestored() {
        Listing listing = saveActive("01M00000000000000000000015");

        assertThat(listingRepository.restoreManualSoldByOwner(
                listing.getId(), SELLER_ID, restoreThreshold())).isZero();
    }

    @Test
    @DisplayName("판매완료·되돌리기는 version을 올린다 - 읽어 둔 수정이 이를 덮어쓰지 못하게")
    void statusTransitionsBumpVersion() {
        Listing listing = saveActive("01M00000000000000000000016");
        Long v0 = listing.getVersion();

        listingRepository.markSoldByOwner(listing.getId(), SELLER_ID, Instant.now());
        Long v1 = listingRepository.findByPublicId(listing.getPublicId()).orElseThrow()
                .getVersion();
        listingRepository.restoreManualSoldByOwner(
                listing.getId(), SELLER_ID, restoreThreshold());
        Long v2 = listingRepository.findByPublicId(listing.getPublicId()).orElseThrow()
                .getVersion();

        assertThat(v1).isGreaterThan(v0);
        assertThat(v2).isGreaterThan(v1);
    }

    @Test
    @DisplayName("판매완료·되돌리기·삭제는 version과 updated_at을 함께 갱신한다")
    void transitionsTouchVersionAndUpdatedAt() {
        Listing listing = saveActive("01M00000000000000000000020");
        String publicId = listing.getPublicId();
        Long v0 = listing.getVersion();
        Instant t0 = listingRepository.findByPublicId(publicId).orElseThrow().getUpdatedAt();

        listingRepository.markSoldByOwner(listing.getId(), SELLER_ID, Instant.now());
        Listing sold = listingRepository.findByPublicId(publicId).orElseThrow();
        listingRepository.restoreManualSoldByOwner(
                listing.getId(), SELLER_ID, restoreThreshold());
        Listing restored = listingRepository.findByPublicId(publicId).orElseThrow();
        listingRepository.softDeleteByOwner(
                listing.getId(), SELLER_ID, Instant.now(), ListingStatus.ACTIVE);
        Listing deleted = listingRepository.findByPublicId(publicId).orElseThrow();

        assertThat(sold.getVersion()).isEqualTo(v0 + 1);
        assertThat(restored.getVersion()).isEqualTo(v0 + 2);
        assertThat(deleted.getVersion()).isEqualTo(v0 + 3);
        assertThat(sold.getUpdatedAt()).isAfter(t0);
        assertThat(restored.getUpdatedAt()).isAfter(sold.getUpdatedAt());
        assertThat(deleted.getUpdatedAt()).isAfter(restored.getUpdatedAt());
    }

    @Test
    @DisplayName("판매 후 삭제된 매물은 판매 기록이 남아 있어도 되돌릴 수 없다")
    void deletedSoldListingCannotBeRestored() {
        Listing listing = saveActive("01M00000000000000000000021");
        listingRepository.markSoldByOwner(listing.getId(), SELLER_ID, Instant.now());
        listingRepository.softDeleteByOwner(
                listing.getId(), SELLER_ID, Instant.now(), ListingStatus.SOLD);

        int affected = listingRepository.restoreManualSoldByOwner(
                listing.getId(), SELLER_ID, restoreThreshold());

        assertThat(affected).isZero();
        assertThat(listingRepository.findByPublicId(listing.getPublicId()).orElseThrow()
                .getStatus()).isEqualTo(ListingStatus.DELETED);
    }

    @Test
    @DisplayName("삭제는 읽어 둔 상태 그대로일 때만 되고, 판매 기록은 남는다")
    void softDeleteKeepsSoldRecord() {
        Listing listing = saveActive("01M00000000000000000000017");
        listingRepository.markSoldByOwner(listing.getId(), SELLER_ID, Instant.now());

        int affected = listingRepository.softDeleteByOwner(
                listing.getId(), SELLER_ID, Instant.now(), ListingStatus.SOLD);

        assertThat(affected).isEqualTo(1);
        Listing reloaded = listingRepository.findByPublicId(listing.getPublicId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ListingStatus.DELETED);
        assertThat(reloaded.getDeletedAt()).isNotNull();
        assertThat(reloaded.getSoldSource()).isEqualTo(SoldSource.MANUAL);
        assertThat(reloaded.getSoldAt()).isNotNull();
    }

    @Test
    @DisplayName("삭제하려고 읽은 사이 판매완료가 커밋되면 삭제는 0행이고 판매완료가 유지된다")
    void softDeleteLosesToConcurrentMarkSold() {
        Listing listing = saveActive("01M00000000000000000000018");
        // 삭제 요청이 ACTIVE로 읽은 뒤, 다른 요청이 판매완료를 커밋했다.
        listingRepository.markSoldByOwner(listing.getId(), SELLER_ID, Instant.now());

        int affected = listingRepository.softDeleteByOwner(
                listing.getId(), SELLER_ID, Instant.now(), ListingStatus.ACTIVE);

        assertThat(affected).isZero();
        Listing reloaded = listingRepository.findByPublicId(listing.getPublicId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ListingStatus.SOLD);
        assertThat(reloaded.getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("남의 매물·이미 삭제된 매물은 지워지지 않는다")
    void softDeleteRejectsOthersAndAlreadyDeleted() {
        Listing listing = saveActive("01M00000000000000000000019");

        assertThat(listingRepository.softDeleteByOwner(
                listing.getId(), 999L, Instant.now(), ListingStatus.ACTIVE)).isZero();
        assertThat(listingRepository.softDeleteByOwner(
                listing.getId(), SELLER_ID, Instant.now(), ListingStatus.ACTIVE)).isEqualTo(1);
        assertThat(listingRepository.softDeleteByOwner(
                listing.getId(), SELLER_ID, Instant.now(), ListingStatus.ACTIVE)).isZero();
    }
}
