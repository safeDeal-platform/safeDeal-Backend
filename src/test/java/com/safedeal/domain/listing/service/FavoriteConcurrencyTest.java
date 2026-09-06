package com.safedeal.domain.listing.service;

import com.safedeal.domain.listing.entity.Category;
import com.safedeal.domain.listing.entity.ItemCondition;
import com.safedeal.domain.listing.entity.Listing;
import com.safedeal.domain.listing.repository.CategoryRepository;
import com.safedeal.domain.listing.repository.ListingFavoriteRepository;
import com.safedeal.domain.listing.repository.ListingRepository;
import com.safedeal.testsupport.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 하트 연타 — 같은 사용자가 같은 매물에 찜 요청을 동시에 여러 번 보내는 상황을 재현한다.
 *
 * <p><b>{@code @Transactional}을 붙이지 않는다.</b> 별도 스레드는 테스트 트랜잭션 밖이라
 * 롤백 대상이 아니고, 롤백 안에 갇히면 다른 스레드가 그 변경을 보지 못해 경쟁이 재현되지
 * 않는다. 대신 {@code @AfterEach}에서 직접 지운다.
 *
 * <p>목으로 예외를 던지게 만드는 단위 테스트로는 이 경로를 증명할 수 없다 — 실제 UNIQUE
 * 제약과 트랜잭션 경계가 있어야만 드러나는 문제이기 때문이다.
 */
class FavoriteConcurrencyTest extends IntegrationTestSupport {

    private static final int THREADS = 8;
    private static final Long USER = 100L;
    private static final String PUBLIC_ID = "01J3ABCDEFGHJKMNPQRSTVWXYZ";

    @Autowired FavoriteCommandService favoriteCommandService;
    @Autowired ListingFavoriteRepository listingFavoriteRepository;
    @Autowired ListingRepository listingRepository;
    @Autowired CategoryRepository categoryRepository;

    private Category phone;

    @BeforeEach
    void setUp() {
        phone = categoryRepository.findByCode("DIGITAL_PHONE").orElseThrow();
        listingFavoriteRepository.deleteAllInBatch();
        listingRepository.deleteAllInBatch();
        listingRepository.saveAndFlush(Listing.register(PUBLIC_ID, 1L, "아이폰", "설명",
                950_000, phone, ItemCondition.USED, "서울특별시", "강남구", false));
    }

    /**
     * 비트랜잭션이라 이 삭제는 커밋된다. 카테고리와 달리 매물은 시더가 없어 되돌릴 기준
     * 상태가 없고, 매물을 쓰는 테스트는 각자 {@code @BeforeEach}에서 자기 것을 만든다.
     * 매물을 미리 만들어 두고 공유하는 비트랜잭션 클래스가 생기면 그때는 여기가 깨진다.
     */
    @AfterEach
    void clear() {
        listingFavoriteRepository.deleteAllInBatch();
        listingRepository.deleteAllInBatch();
    }

    /** 모든 스레드를 같은 순간에 풀어 경합 창을 넓힌다. 실패는 삼키지 않고 그대로 올린다. */
    private List<Throwable> runConcurrently(Runnable action) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Callable<Throwable>> tasks = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                tasks.add(() -> {
                    // 타임아웃이 없으면 스레드 하나가 배리어 전에 죽었을 때 나머지가
                    // 무기한 대기하고 invokeAll도 끝나지 않아 CI가 그대로 멈춘다.
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        action.run();
                        return null;
                    } catch (Throwable t) {
                        return t;
                    }
                });
            }
            List<Throwable> failures = new ArrayList<>();
            for (Future<Throwable> future : pool.invokeAll(tasks)) {
                Throwable t = future.get();
                if (t != null) {
                    failures.add(t);
                }
            }
            return failures;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("같은 매물을 동시에 여러 번 찜해도 전부 성공하고 행은 하나만 남는다")
    void concurrentAddIsIdempotent() throws Exception {
        List<Throwable> failures =
                runConcurrently(() -> favoriteCommandService.add(USER, PUBLIC_ID));

        assertThat(failures)
                .withFailMessage("동시 찜에서 예외가 났다: %s", failures)
                .isEmpty();
        assertThat(listingFavoriteRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 찜을 동시에 여러 번 해제해도 전부 성공하고 행은 사라진다")
    void concurrentRemoveIsIdempotent() throws Exception {
        favoriteCommandService.add(USER, PUBLIC_ID);

        List<Throwable> failures =
                runConcurrently(() -> favoriteCommandService.remove(USER, PUBLIC_ID));

        assertThat(failures)
                .withFailMessage("동시 해제에서 예외가 났다: %s", failures)
                .isEmpty();
        assertThat(listingFavoriteRepository.count()).isZero();
    }

    @Test
    @DisplayName("찜한 뒤 가격이 올라도 재찜이 몰리는 것만으로는 기준가가 오르지 않는다")
    void keepsFirstBasePriceUnderConcurrency() throws Exception {
        favoriteCommandService.add(USER, PUBLIC_ID);

        // 매물 가격을 올려두지 않으면 모든 스레드가 기준가와 같은 값을 써서, ON DUPLICATE KEY
        // UPDATE를 덮어쓰기로 바꿔도 테스트가 통과한다. 값이 달라야 가드가 검증된다.
        Listing listing = listingRepository.findByPublicId(PUBLIC_ID).orElseThrow();
        listing.update("아이폰", "설명", 1_200_000, phone, ItemCondition.USED, LocalDate.now());
        listingRepository.saveAndFlush(listing);

        runConcurrently(() -> favoriteCommandService.add(USER, PUBLIC_ID));

        assertThat(listingFavoriteRepository.findByUserIdAndListing(USER, listing).orElseThrow()
                .getNotifyBasePrice()).isEqualTo(950_000);
    }
}
