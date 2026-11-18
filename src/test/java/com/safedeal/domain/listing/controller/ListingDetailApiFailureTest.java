package com.safedeal.domain.listing.controller;

import com.safedeal.domain.listing.entity.Listing;
import com.safedeal.domain.listing.repository.ListingFavoriteRepository;
import com.safedeal.domain.listing.service.FavoriteCommandService;
import com.safedeal.domain.listing.service.ListingCommandService;
import com.safedeal.domain.user.entity.User;
import com.safedeal.domain.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 매물 상세 확장의 장애·실행 순서·조회 방식 케이스. 저장소·Redis·조회수 서비스를 spy로 바꿔야 해서
 * {@link ListingDetailApiTest}와 컨텍스트를 나눈다(spy가 다른 테스트에 새지 않도록).
 */
class ListingDetailApiFailureTest extends ListingDetailApiSupport {

    @MockitoSpyBean UserRepository userRepositorySpy;
    @MockitoSpyBean ListingFavoriteRepository listingFavoriteRepositorySpy;
    @MockitoSpyBean StringRedisTemplate redisTemplate;
    @MockitoSpyBean ListingCommandService listingCommandService;
    @Autowired FavoriteCommandService favoriteCommandService;

    @Test
    @DisplayName("LD-25 판매자 조회 중 DB 장애면 seller만 비우지 않고 전체 500 C007, 본문에 SQL이 없다 (Q8)")
    void databaseFailureIsInternalError() throws Exception {
        Listing listing = listingOf(user());
        doThrow(new DataAccessResourceFailureException("Communications link failure: select * from users"))
                .when(userRepositorySpy).findById(anyLong());

        String body = mockMvc.perform(detail(listing))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("C007"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContainIgnoringCase("select").doesNotContain("Communications link failure");
    }

    @Test
    @DisplayName("LD-26 조회수 증가가 실패해도 확장 필드를 담아 200")
    void viewCountFailureKeepsExtendedFields() throws Exception {
        User me = user();
        User seller = user();
        Listing listing = listingOf(seller);
        favorite(me.getId(), listing);
        doThrow(new RuntimeException("lock wait timeout"))
                .when(listingCommandService).increaseViewCount(any(), any(), anyBoolean());

        mockMvc.perform(detailAs(me, listing))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.seller.publicId").value(seller.getPublicId()))
                .andExpect(jsonPath("$.data.favoriteCount").value(1))
                .andExpect(jsonPath("$.data.favorited").value(true));
    }

    @Test
    @DisplayName("LD-27 Redis 장애 중에도 유효한 토큰이면 로그인으로 처리해 favorited를 계산한다 (블랙리스트 fail-open)")
    void redisFailureStillTreatsTokenAsLoggedIn() throws Exception {
        User me = user();
        Listing listing = listingOf(user());
        favorite(me.getId(), listing);
        doThrow(new RedisConnectionFailureException("redis down")).when(redisTemplate).hasKey(anyString());

        mockMvc.perform(detailAs(me, listing))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.favorited").value(true));

        // 블랙리스트 확인을 실제로 시도했다가 실패한 것인지 본다 — 확인을 건너뛰어 통과하는 것을 막는다.
        verify(redisTemplate, atLeastOnce()).hasKey(anyString());
    }

    @Test
    @DisplayName("LD-29 조회가 찜 쿼리 하나를 읽은 직후 찜 등록이 커밋돼도 찜 수와 favorited가 서로 맞고, 등록 뒤 조회는 (1, true)")
    void countAndFavoritedAreConsistentUnderConcurrentAdd() throws Exception {
        User me = user();
        Listing listing = listingOf(user());
        CountDownLatch firstFavoriteQueryDone = new CountDownLatch(1);
        CountDownLatch favoriteAdded = new CountDownLatch(1);
        AtomicBoolean paused = new AtomicBoolean();
        // 찜 수·내 찜 중 먼저 불린 쪽(순서는 구현 재량)이 실제 값을 읽은 뒤 한 번만 멈추고, 그 사이 찜 등록을 커밋한다.
        Answer<Object> pauseAfterFirstQuery = invocation -> {
            Object real = callThrough(invocation);
            if (paused.compareAndSet(false, true)) {
                firstFavoriteQueryDone.countDown();
                assertThat(favoriteAdded.await(10, TimeUnit.SECONDS)).as("찜 등록 커밋 대기").isTrue();
            }
            return real;
        };
        doAnswer(pauseAfterFirstQuery).when(listingFavoriteRepositorySpy).countByListingId(anyLong());
        doAnswer(pauseAfterFirstQuery).when(listingFavoriteRepositorySpy).existsByUserIdAndListingId(anyLong(), anyLong());

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> read = pool.submit(() -> mockMvc.perform(detailAs(me, listing)).andReturn());
            assertThat(firstFavoriteQueryDone.await(10, TimeUnit.SECONDS)).as("조회가 찜 쿼리 하나를 끝내고 멈춤").isTrue();
            favoriteCommandService.add(me.getId(), listing.getPublicId()); // 다른 트랜잭션에서 커밋된다
            favoriteAdded.countDown();

            MvcResult interleaved = read.get(10, TimeUnit.SECONDS);
            MvcResult afterAdd = mockMvc.perform(detailAs(me, listing)).andReturn();

            assertThat(interleaved.getResponse().getStatus()).as("끼어든 조회 상태").isEqualTo(200);
            assertThat(afterAdd.getResponse().getStatus()).as("등록 뒤 조회 상태").isEqualTo(200);
            // 두 쿼리가 한 읽기 스냅샷을 봐야 [0,false]가 된다. 따로 읽으면 [0,true]나 [1,false]가 나온다.
            assertThat(countAndFavorited(interleaved)).as("끼어든 조회 [favoriteCount, favorited]")
                    .isIn(List.of(0, false), List.of(1, true));
            assertThat(countAndFavorited(afterAdd)).as("등록 뒤 조회 [favoriteCount, favorited]")
                    .isEqualTo(List.of(1, true));
        } finally {
            favoriteAdded.countDown();
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("LD-32 판매자 조회 메서드 고정 — 판매자는 findById로 읽고 잠금 쿼리를 부르지 않는다 (D4)")
    void sellerLookupMethodIsPinned() throws Exception {
        User seller = user();
        Listing listing = listingOf(seller);

        mockMvc.perform(detail(listing)).andExpect(status().isOk());

        // 지키는 것: 판매자 조회가 FOR UPDATE 쿼리(신뢰도 갱신·비밀번호 재설정용)로 바뀌면 상세가 그 잠금을 기다린다.
        // 한계: 메서드 이름만 본다. 실제로 기다리지 않는지는 ListingDetailApiTest.notBlockedBySellerRowLock이 본다.
        verify(userRepositorySpy).findById(seller.getId());
        verify(userRepositorySpy, never()).findForTrustScoreUpdate(anyLong());
        verify(userRepositorySpy, never()).findForPasswordResetUpdate(anyLong());
    }

    /** spy의 기본 동작(실제 저장소 호출)으로 넘긴다. Spring이 JDK 프록시 저장소를 spy로 감싸는 방식과 무관하게 동작한다. */
    private static Object callThrough(InvocationOnMock invocation) throws Throwable {
        return mockingDetails(invocation.getMock()).getMockCreationSettings().getDefaultAnswer().answer(invocation);
    }

    private List<Object> countAndFavorited(MvcResult result) throws Exception {
        Map<String, Object> data = dataOf(result);
        return Arrays.asList(data.get("favoriteCount"), data.get("favorited"));
    }
}
