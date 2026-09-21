package com.safedeal.domain.chat.service;

import com.safedeal.domain.chat.dto.ChatRoomCreateRequest;
import com.safedeal.domain.chat.dto.ChatRoomCreateResponse;
import com.safedeal.domain.chat.repository.ChatRoomRepository;
import com.safedeal.domain.listing.entity.Category;
import com.safedeal.domain.listing.entity.ItemCondition;
import com.safedeal.domain.listing.entity.Listing;
import com.safedeal.domain.listing.repository.CategoryRepository;
import com.safedeal.domain.listing.repository.ListingRepository;
import com.safedeal.testsupport.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API 명세서 CHT-1 테스트 항목 "동시 2요청 → 방 1개만 생성"을 실제로 재현한다.
 *
 * <p><b>{@code @Transactional}을 붙이지 않는다</b> — 별도 스레드에서 실행되는 요청은 테스트
 * 트랜잭션 밖이라 롤백이 통하지 않는다({@link IntegrationTestSupport} 클래스 주석). 대신
 * {@link #cleanUp()}에서 직접 정리한다. 고정 sleep 대신 {@link CountDownLatch}로 두 스레드를
 * 동시에 출발시킨다.
 */
class ChatRoomConcurrencyTest extends IntegrationTestSupport {

    @Autowired
    ChatRoomCommandService chatRoomCommandService;

    @Autowired
    ChatRoomRepository chatRoomRepository;

    @Autowired
    ListingRepository listingRepository;

    @Autowired
    CategoryRepository categoryRepository;

    private Listing listing;

    @BeforeEach
    void setUp() {
        Category phone = categoryRepository.findByCode("DIGITAL_PHONE").orElseThrow();
        listing = listingRepository.saveAndFlush(Listing.register(
                "01J3ARSNIPCONCURRENCY001", 30L, "동시성 테스트용 매물", "설명", 500_000,
                phone, ItemCondition.USED, "서울특별시", "강남구", false));
    }

    @AfterEach
    void cleanUp() {
        chatRoomRepository.deleteAllInBatch();
        listingRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("동시 2요청 → 방은 1개만 생성되고, 정확히 한쪽만 created=true다")
    void concurrentRequests_createExactlyOneRoom() throws InterruptedException {
        ChatRoomCreateRequest request = new ChatRoomCreateRequest(listing.getPublicId());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        List<Future<ChatRoomCreateResponse>> futures = List.of(
                executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return chatRoomCommandService.open(20L, request);
                }),
                executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return chatRoomCommandService.open(20L, request);
                })
        );

        ready.await();
        start.countDown();

        List<ChatRoomCreateResponse> responses = futures.stream()
                .map(f -> {
                    try {
                        return f.get(10, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .toList();
        executor.shutdown();

        assertThat(responses).extracting(ChatRoomCreateResponse::created)
                .containsExactlyInAnyOrder(true, false);
        assertThat(responses.get(0).roomId()).isEqualTo(responses.get(1).roomId());
        assertThat(chatRoomRepository.findByBuyerIdAndListingId(20L, listing.getId())).isPresent();
        assertThat(chatRoomRepository.findAll()).hasSize(1);
    }
}
