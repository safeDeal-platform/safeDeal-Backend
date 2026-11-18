package com.safedeal.domain.listing.controller;

import com.safedeal.domain.listing.entity.Listing;
import com.safedeal.domain.listing.service.FavoriteCommandService;
import com.safedeal.domain.user.entity.User;
import com.safedeal.global.security.JwtProperties;
import com.safedeal.global.security.JwtTokenProvider;
import com.safedeal.global.security.TokenBlacklist;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/listings/{publicId} — seller·favoriteCount·favorited 확장 (LD-01~45).
 * 인증은 실제 JWT(Bearer)로 채운다. 장애 주입·실행 순서 고정(spy)이 필요한 케이스는 {@link ListingDetailApiFailureTest}.
 * 이 컨텍스트에만 {@link RecordingStatementInspector}를 붙여 구현이 실제로 낸 SQL을 본다(LD-44).
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties =
        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                + "com.safedeal.domain.listing.controller.RecordingStatementInspector")
class ListingDetailApiTest extends ListingDetailApiSupport {

    /**
     * 확장 전(dev bcbe494) 상세 한 번에 나가는 JDBC 문장 수 — 2026-11-18 Hibernate Statistics로 측정.
     * 비로그인·로그인 모두 [매물 조회 1, 카테고리 1, 부모 카테고리 1, 조회수 UPDATE 1]이다.
     */
    private static final long BASELINE_STATEMENTS = 4;
    /** 확장 전 404 경로: 매물 조회 1. */
    private static final long BASELINE_NOT_FOUND_STATEMENTS = 1;

    @Autowired FavoriteCommandService favoriteCommandService;
    @Autowired TokenBlacklist tokenBlacklist;
    @Autowired JwtProperties jwtProperties;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManagerFactory entityManagerFactory;

    // ── 정상 ──────────────────────────────────────────────

    @Test
    @DisplayName("LD-01 비로그인으로 ACTIVE 매물을 보면 200, seller·favoriteCount가 채워지고 favorited는 null (Q2)")
    void anonymousGetsSellerAndCount() throws Exception {
        User seller = user();
        Listing listing = listingOf(seller);
        favorite(9_001L, listing);

        MvcResult result = mockMvc.perform(detail(listing))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.seller.publicId").value(seller.getPublicId()))
                .andExpect(jsonPath("$.data.seller.nickname").value(seller.getNickname()))
                .andExpect(jsonPath("$.data.favoriteCount").value(1))
                .andReturn();

        // 숫자로 비교한다(결정 A) — 50과 50.0 직렬화 차이에 흔들리지 않는다. 소수 1자리는 서비스 단위 테스트가 고정한다.
        Object trustScore = sellerOf(result).get("trustScore");
        assertThat(trustScore).isInstanceOf(Number.class);
        assertThat(new BigDecimal(trustScore.toString())).isEqualByComparingTo("50.0");
        // false와 구분되는 null이어야 한다 — 키가 아예 없으면 프론트가 "없는 필드"로 오해한다.
        assertThat(dataOf(result)).containsEntry("favorited", null);
    }

    @Test
    @DisplayName("LD-02 내가 찜한 매물이면 favorited=true")
    void favoritedByMe() throws Exception {
        User me = user();
        Listing listing = listingOf(user());
        favorite(me.getId(), listing);

        mockMvc.perform(detailAs(me, listing))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.favorited").value(true))
                .andExpect(jsonPath("$.data.favoriteCount").value(1));
    }

    @Test
    @DisplayName("LD-03 남만 찜했고 나는 안 했으면 favorited=false")
    void notFavoritedByMe() throws Exception {
        User me = user();
        Listing listing = listingOf(user());
        favorite(9_002L, listing);

        mockMvc.perform(detailAs(me, listing))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.favorited").value(false))
                .andExpect(jsonPath("$.data.favoriteCount").value(1));
    }

    @Test
    @DisplayName("LD-04 SOLD 매물도 ACTIVE와 같은 규칙으로 채운다")
    void soldListingFollowsSameRules() throws Exception {
        User me = user();
        User seller = user();
        Listing listing = listingOf(seller);
        favorite(me.getId(), listing);
        sql("UPDATE listings SET status = 'SOLD' WHERE id = ?", listing.getId());

        mockMvc.perform(detailAs(me, listing))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SOLD"))
                .andExpect(jsonPath("$.data.seller.publicId").value(seller.getPublicId()))
                .andExpect(jsonPath("$.data.favoriteCount").value(1))
                .andExpect(jsonPath("$.data.favorited").value(true));
    }

    @ParameterizedTest(name = "LD-05 내부 {0} → {1}")
    @CsvSource({"0, 0.0", "300, 30.0", "500, 50.0", "1000, 100.0"})
    @DisplayName("LD-05 seller.trustScore는 TrustScore.display 표시값(소수 1자리)")
    void sellerTrustScoreIsDisplayValue(int internal, BigDecimal expected) throws Exception {
        User seller = user();
        sql("UPDATE users SET trust_score = ? WHERE id = ?", internal, seller.getId());
        Listing listing = listingOf(seller);

        Map<String, Object> sellerJson = sellerOf(mockMvc.perform(detail(listing))
                .andExpect(status().isOk()).andReturn());
        assertThat(sellerJson).as("seller").isNotNull();
        Object trustScore = sellerJson.get("trustScore");

        // 숫자로 비교한다 — 30과 30.0 직렬화 차이에 흔들리지 않고, 300(원값)이나 3.0(÷100)은 잡는다.
        // 소수 1자리(scale)는 서비스 단위 테스트(ListingDetailQueryServiceTest.anonymousDetail)가 BigDecimal로 고정한다.
        assertThat(trustScore).isInstanceOf(Number.class);
        assertThat(new BigDecimal(trustScore.toString())).isEqualByComparingTo(expected);
    }

    @Test
    @DisplayName("LD-06 LD-36 seller 키는 publicId·nickname·trustScore 정확히 3개이고, publicId는 내부 id가 아니다")
    void sellerKeysAreWhitelisted() throws Exception {
        User seller = user();
        Listing listing = listingOf(seller);

        Map<String, Object> sellerJson = sellerOf(mockMvc.perform(detail(listing))
                .andExpect(status().isOk()).andReturn());

        assertThat(sellerJson).containsOnlyKeys(SELLER_KEYS);
        assertThat(sellerJson.get("publicId"))
                .isEqualTo(seller.getPublicId())
                .isNotEqualTo(String.valueOf(seller.getId()));
    }

    // ── 찜 수 경계 ────────────────────────────────────────

    @Test
    @DisplayName("LD-07 찜이 0개면 favoriteCount는 null이 아니라 0")
    void zeroFavorites() throws Exception {
        Listing listing = listingOf(user());

        MvcResult result = mockMvc.perform(detail(listing)).andExpect(status().isOk()).andReturn();

        assertThat(dataOf(result)).containsEntry("favoriteCount", 0);
    }

    @Test
    @DisplayName("LD-08 판매자 본인의 찜도 센다 — 판매자에게는 favorited=true (Q5)")
    void sellerOwnFavoriteCounts() throws Exception {
        User seller = user();
        Listing listing = listingOf(seller);
        favorite(seller.getId(), listing);

        mockMvc.perform(detailAs(seller, listing))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.favoriteCount").value(1))
                .andExpect(jsonPath("$.data.favorited").value(true));
    }

    @Test
    @DisplayName("LD-09 찜 1,000개면 정확히 1000")
    void thousandFavorites() throws Exception {
        Listing listing = listingOf(user());
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            rows.add(new Object[]{1_000_000L + i, listing.getId(), listing.getPrice()});
        }
        jdbc.batchUpdate("INSERT INTO listing_favorites (user_id, listing_id, notify_base_price, created_at, updated_at) "
                + "VALUES (?, ?, ?, NOW(6), NOW(6))", rows);

        mockMvc.perform(detail(listing))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.favoriteCount").value(1_000));
    }

    @Test
    @DisplayName("LD-10 LD-11 다른 매물의 찜은 세지 않고, 내가 다른 매물만 찜했으면 이 매물은 false")
    void otherListingsFavoritesDoNotLeak() throws Exception {
        User me = user();
        User seller = user();
        Listing target = listingOf(seller);
        Listing other = listingOf(seller);
        favorite(me.getId(), other);
        favorite(9_003L, other);
        favorite(9_004L, target);

        mockMvc.perform(detailAs(me, target))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.favoriteCount").value(1))
                .andExpect(jsonPath("$.data.favorited").value(false));
    }

    @Test
    @DisplayName("LD-12 찜을 해제한 직후 조회하면 수가 1 줄고 false")
    void afterUnfavorite() throws Exception {
        User me = user();
        Listing listing = listingOf(user());
        favorite(9_005L, listing);
        mockMvc.perform(post("/api/listings/" + listing.getPublicId() + "/favorite")
                .header(HttpHeaders.AUTHORIZATION, bearer(me))).andExpect(status().isCreated());
        mockMvc.perform(detailAs(me, listing))
                .andExpect(jsonPath("$.data.favoriteCount").value(2))
                .andExpect(jsonPath("$.data.favorited").value(true));

        mockMvc.perform(delete("/api/listings/" + listing.getPublicId() + "/favorite")
                .header(HttpHeaders.AUTHORIZATION, bearer(me))).andExpect(status().isOk());

        mockMvc.perform(detailAs(me, listing))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.favoriteCount").value(1))
                .andExpect(jsonPath("$.data.favorited").value(false));
    }

    // ── 404 회귀 ──────────────────────────────────────────

    @Test
    @DisplayName("LD-13 없는 매물은 404 C002")
    void unknownListingIsNotFound() throws Exception {
        mockMvc.perform(get("/api/listings/01JZZZZZZZZZZZZZZZZZZZZZZZ"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("C002"));
    }

    @ParameterizedTest(name = "LD-14 {0}")
    @ValueSource(strings = {
            "UPDATE listings SET deleted_at = NOW(6) WHERE id = ?",
            "UPDATE listings SET status = 'BLOCKED' WHERE id = ?",
            "UPDATE listings SET status = 'DRAFT' WHERE id = ?",
            "UPDATE listings SET status = 'PENDING_VERIFICATION' WHERE id = ?"})
    @DisplayName("LD-14 삭제·BLOCKED·DRAFT·PENDING 매물은 기존대로 404 (판매자 본인이 찜해 둬도)")
    void hiddenListingsStayNotFound(String hide) throws Exception {
        User seller = user();
        Listing listing = listingOf(seller);
        favorite(seller.getId(), listing);
        sql(hide, listing.getId());

        mockMvc.perform(detailAs(seller, listing))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("C002"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    // ── 권한 ──────────────────────────────────────────────

    @Test
    @DisplayName("LD-15 판매자 본인이 보면 200이고 조회수는 오르지 않는다")
    void sellerViewsOwnListing() throws Exception {
        User seller = user();
        Listing listing = listingOf(seller);

        mockMvc.perform(detailAs(seller, listing))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.seller.publicId").value(seller.getPublicId()))
                .andExpect(jsonPath("$.data.favorited").value(false));

        assertThat(viewCountOf(listing)).isZero();
    }

    @Test
    @DisplayName("LD-16 만료된 토큰은 401이 아니라 비로그인으로 200, favorited=null (Q3)")
    void expiredTokenIsAnonymous() throws Exception {
        User me = user();
        Listing listing = listingOf(user());
        favorite(me.getId(), listing);
        String expired = providerWith(jwtProperties.getSecret(), Duration.ofMinutes(-1))
                .issueAccess(me.getId(), "USER").value();

        expectAnonymous(detail(listing).header(HttpHeaders.AUTHORIZATION, "Bearer " + expired));
    }

    @Test
    @DisplayName("LD-17 다른 키로 서명된 토큰은 비로그인으로 처리")
    void forgedTokenIsAnonymous() throws Exception {
        User me = user();
        Listing listing = listingOf(user());
        favorite(me.getId(), listing);
        String forged = providerWith("another-secret-key-for-forgery-test-32bytes!", Duration.ofMinutes(30))
                .issueAccess(me.getId(), "USER").value();

        expectAnonymous(detail(listing).header(HttpHeaders.AUTHORIZATION, "Bearer " + forged));
    }

    @Test
    @DisplayName("LD-17 refresh 토큰을 Bearer로 보내면 비로그인으로 처리")
    void refreshTokenIsAnonymous() throws Exception {
        User me = user();
        Listing listing = listingOf(user());
        favorite(me.getId(), listing);
        String refresh = tokenProvider.issueRefresh(me.getId()).value();

        expectAnonymous(detail(listing).header(HttpHeaders.AUTHORIZATION, "Bearer " + refresh));
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @ValueSource(strings = {"Bearer not-a-jwt", "Bearer ", "Token abc.def.ghi"})
    @DisplayName("LD-17 형식이 틀린 Authorization은 비로그인으로 처리")
    void malformedTokenIsAnonymous(String authorization) throws Exception {
        Listing listing = listingOf(user());

        expectAnonymous(detail(listing).header(HttpHeaders.AUTHORIZATION, authorization));
    }

    @Test
    @DisplayName("LD-18 로그아웃(블랙리스트)한 토큰은 비로그인으로 처리")
    void blacklistedTokenIsAnonymous() throws Exception {
        User me = user();
        Listing listing = listingOf(user());
        favorite(me.getId(), listing);
        JwtTokenProvider.IssuedToken token = tokenProvider.issueAccess(me.getId(), "USER");
        tokenBlacklist.add(token.jti(), token.expiresAt());

        expectAnonymous(detail(listing).header(HttpHeaders.AUTHORIZATION, "Bearer " + token.value()));
    }

    @Test
    @DisplayName("LD-19 관리자가 보면 200이고 favorited는 관리자 본인의 찜 기준, 조회수는 오르지 않는다")
    void adminSeesOwnFavorite() throws Exception {
        User admin = user();
        sql("UPDATE users SET role = 'ADMIN' WHERE id = ?", admin.getId());
        Listing listing = listingOf(user());
        favorite(admin.getId(), listing);

        mockMvc.perform(detail(listing).header(HttpHeaders.AUTHORIZATION, bearer(admin.getId(), "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.favorited").value(true))
                .andExpect(jsonPath("$.data.favoriteCount").value(1));

        assertThat(viewCountOf(listing)).isZero();
    }

    @ParameterizedTest(name = "LD-20 {0}")
    @ValueSource(strings = {
            "UPDATE users SET status = 'SUSPENDED' WHERE id = ?",
            "UPDATE users SET status = 'BANNED' WHERE id = ?",
            "UPDATE users SET deleted_at = NOW(6) WHERE id = ?"})
    @DisplayName("LD-20 보는 사람이 정지·탈퇴여도 상태를 확인하지 않고 찜 여부를 계산한다 (Q9)")
    void viewerStatusIsNotChecked(String restrict) throws Exception {
        User me = user();
        Listing listing = listingOf(user());
        favorite(me.getId(), listing);
        sql(restrict, me.getId());

        mockMvc.perform(detailAs(me, listing))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.favorited").value(true));
    }

    @Test
    @DisplayName("LD-21 쿼리·헤더로 남의 id를 넣어도 무시하고 토큰 주인 기준으로 계산한다")
    void ignoresOtherUserIdentifiers() throws Exception {
        User me = user();
        User other = user();
        Listing listing = listingOf(user());
        favorite(other.getId(), listing);

        mockMvc.perform(detailAs(me, listing)
                        .param("userId", String.valueOf(other.getId()))
                        .param("viewerId", String.valueOf(other.getId()))
                        .header("X-Dev-User-Id", String.valueOf(other.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.favorited").value(false))
                .andExpect(jsonPath("$.data.favoriteCount").value(1));
    }

    // ── 판매자 상태 ────────────────────────────────────────

    @Test
    @DisplayName("LD-22 판매자가 탈퇴했으면 매물은 열리고 seller는 null, WARN 로그는 남기지 않는다 (Q1·D1)")
    void deletedSellerIsNull(CapturedOutput output) throws Exception {
        User seller = user();
        Listing listing = listingOf(seller);
        sql("UPDATE users SET deleted_at = NOW(6) WHERE id = ?", seller.getId());
        int before = output.getOut().length();

        MvcResult result = mockMvc.perform(detail(listing))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publicId").value(listing.getPublicId()))
                .andReturn();

        assertThat(dataOf(result)).containsEntry("seller", null).containsEntry("favoriteCount", 0);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(seller.getNickname());
        // 탈퇴는 정상 상태다 — WARN은 행이 아예 없을 때(LD-24)만. 탈퇴까지 WARN이면 운영 경보가 잡음에 묻힌다.
        // 이 매물 publicId가 든 WARN만 센다(결정 B) — 같은 시각 다른 기능의 WARN으로 흔들리지 않게. D2에 따라 이 기능의 WARN에는 publicId가 반드시 들어간다.
        assertThat(output.getOut().substring(before).lines())
                .as("탈퇴 판매자 조회 중 이 매물에 대한 WARN 로그가 없어야 한다 (D1)")
                .noneMatch(line -> line.contains("WARN") && line.contains(listing.getPublicId()));
    }

    @ParameterizedTest(name = "LD-23 {0}")
    @ValueSource(strings = {"SUSPENDED", "BANNED"})
    @DisplayName("LD-23 판매자가 정지·영구정지여도 평소처럼 노출한다 (Q6)")
    void restrictedSellerIsShown(String status) throws Exception {
        User seller = user();
        Listing listing = listingOf(seller);
        sql("UPDATE users SET status = ? WHERE id = ?", status, seller.getId());

        mockMvc.perform(detail(listing))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.seller.publicId").value(seller.getPublicId()))
                .andExpect(jsonPath("$.data.seller.nickname").value(seller.getNickname()));
    }

    @Test
    @DisplayName("LD-24 LD-37 판매자 행이 없으면 seller는 null, WARN 한 줄에 매물 publicId와 내부 sellerId를 남기고 email·토큰 원문은 남기지 않는다 (Q7·D2)")
    void missingSellerRowIsNullWithWarn(CapturedOutput output) throws Exception {
        long missingSellerId = Long.MAX_VALUE; // users에 없는 id. listings.seller_id에는 FK가 없다.
        Listing listing = listingOf(missingSellerId, categoryRepository.findByCode("DIGITAL_PHONE").orElseThrow());
        User viewer = user();
        String authorization = bearer(viewer);
        int before = output.getOut().length();

        MvcResult result = mockMvc.perform(detail(listing).header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isOk()).andReturn();

        assertThat(dataOf(result)).containsEntry("seller", null);
        String duringRequest = output.getOut().substring(before);
        // 운영자가 한 줄로 어느 매물이 어느 판매자를 잃었는지 찾을 수 있어야 한다 (D2).
        assertThat(duringRequest.lines())
                .as("WARN 한 줄에 매물 publicId(%s)와 sellerId(%d)가 함께 있어야 한다", listing.getPublicId(), missingSellerId)
                .anyMatch(line -> line.contains("WARN")
                        && line.contains(listing.getPublicId())
                        && line.contains(String.valueOf(missingSellerId)));
        // LD-37 실패 경로: 경고를 남기는 경로에서도 보는 사람 email·토큰 원문이 새지 않는다.
        assertThat(duringRequest)
                .doesNotContain(viewer.getEmail())
                .doesNotContain(authorization.substring("Bearer ".length()));
    }

    // ── 중복·동시성 ────────────────────────────────────────

    @Test
    @DisplayName("LD-28 연속 조회는 매번 같은 seller·favoriteCount·favorited")
    void repeatedReadsAreStable() throws Exception {
        User me = user();
        Listing listing = listingOf(user());
        favorite(me.getId(), listing);

        Map<String, Object> first = dataOf(mockMvc.perform(detailAs(me, listing)).andReturn());
        Map<String, Object> second = dataOf(mockMvc.perform(detailAs(me, listing)).andReturn());

        assertThat(first).containsEntry("favoriteCount", 1).containsEntry("favorited", true);
        assertThat(second.get("seller")).isNotNull().isEqualTo(first.get("seller"));
        assertThat(second.get("favoriteCount")).isEqualTo(first.get("favoriteCount"));
        assertThat(second.get("favorited")).isEqualTo(first.get("favorited"));
    }

    // LD-29(찜 등록과 조회의 교차)는 실행 순서를 spy로 고정해야 해서 ListingDetailApiFailureTest에 있다.

    @Test
    @DisplayName("LD-30 8명이 동시에 찜한 뒤 조회하면 favoriteCount가 실제 행 수(8)와 같다 — 동시 등록이 끝난 뒤의 값을 본다")
    void concurrentFavoritesAreCountedExactly() throws Exception {
        Listing listing = listingOf(user());
        List<Callable<Map<String, Object>>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            long userId = 2_000_000L + i;
            tasks.add(() -> {
                favoriteCommandService.add(userId, listing.getPublicId());
                return null;
            });
        }
        runConcurrently(tasks);

        assertThat(favoriteRows(listing)).isEqualTo(8);
        mockMvc.perform(detail(listing))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.favoriteCount").value(8));
    }

    @Test
    @DisplayName("LD-31 상세를 본 뒤 판매자가 그 version으로 수정하면 409 없이 성공한다 — 조회가 version을 올리지 않는다")
    void detailDoesNotBumpVersion() throws Exception {
        User seller = user();
        User viewer = user();
        Listing listing = listingOf(seller);
        favorite(viewer.getId(), listing);

        MvcResult seen = mockMvc.perform(detailAs(viewer, listing))
                .andExpect(jsonPath("$.data.favorited").value(true))
                .andReturn();
        mockMvc.perform(detail(listing)).andExpect(status().isOk());
        long version = ((Number) dataOf(seen).get("version")).longValue();

        assertThat(listingRepository.findById(listing.getId()).orElseThrow().getVersion()).isEqualTo(version);
        mockMvc.perform(patch("/api/listings/" + listing.getPublicId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"아이폰 수정","price":900000,"categoryCode":"DIGITAL_PHONE",
                                 "description":"설명","itemCondition":"USED","regionSido":"서울특별시",
                                 "regionSigungu":"강남구","version":%d}""".formatted(version)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("LD-32 신뢰도 갱신이 판매자 행을 잠그고 있어도 상세 조회는 기다리지 않는다")
    void notBlockedBySellerRowLock() throws Exception {
        User seller = user();
        Listing listing = listingOf(seller);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
                userRepository.findForTrustScoreUpdate(seller.getId()).orElseThrow();
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).as("잠금 획득").isTrue();

            Future<MvcResult> read = pool.submit(() -> mockMvc.perform(detail(listing)).andReturn());

            // 잠금 읽기(FOR UPDATE)로 판매자를 읽으면 잠금이 풀릴 때까지(innodb_lock_wait_timeout) 기다린다.
            MvcResult result = read.get(5, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(sellerOf(result)).containsEntry("publicId", seller.getPublicId());
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    // ── 성능 (Q4 A', Q12) ─────────────────────────────────

    @Test
    @DisplayName("LD-33 LD-45 비로그인 상세의 JDBC 문장 수는 확장 전 + 2 이하 (판매자 1, 찜 수 1)")
    void anonymousStatementCeiling() throws Exception {
        Listing listing = listingOf(user());

        long statements = statementsDuring(() -> mockMvc.perform(detail(listing))
                .andExpect(jsonPath("$.data.favoriteCount").value(0))
                .andExpect(jsonPath("$.data.seller.publicId").exists()));

        assertThat(statements)
                .as("비로그인 문장 수: 확장 전 %d(매물·카테고리·부모 카테고리·조회수 UPDATE) 이상이어야 측정이 살아 있는 것이고,"
                        + " +2(판매자·찜 수)를 넘으면 쿼리가 늘어난 것", BASELINE_STATEMENTS)
                .isBetween(BASELINE_STATEMENTS, BASELINE_STATEMENTS + 2);
    }

    @Test
    @DisplayName("LD-33 LD-45 로그인 상세의 JDBC 문장 수는 확장 전 + 3 이하 (판매자 1, 찜 수 1, 내 찜 1)")
    void loggedInStatementCeiling() throws Exception {
        User me = user();
        Listing listing = listingOf(user());

        long statements = statementsDuring(() -> mockMvc.perform(detailAs(me, listing))
                .andExpect(jsonPath("$.data.favorited").value(false))
                .andExpect(jsonPath("$.data.seller.publicId").exists()));

        assertThat(statements)
                .as("로그인 문장 수: 확장 전 %d 이상이어야 측정이 살아 있는 것이고,"
                        + " +3(판매자·찜 수·내 찜)을 넘으면 쿼리가 늘어난 것", BASELINE_STATEMENTS)
                .isBetween(BASELINE_STATEMENTS, BASELINE_STATEMENTS + 3);
    }

    @Test
    @DisplayName("LD-13 LD-34 404 경로(없는 매물·BLOCKED 매물)는 확장 전과 같은 문장 수 — 판매자·찜 쿼리가 나가지 않는다")
    void notFoundIssuesNoExtraStatements() throws Exception {
        User me = user();
        Listing blocked = listingOf(user());
        favorite(me.getId(), blocked);
        sql("UPDATE listings SET status = 'BLOCKED' WHERE id = ?", blocked.getId());

        long unknown = statementsDuring(() -> mockMvc.perform(get("/api/listings/01JZZZZZZZZZZZZZZZZZZZZZZZ")
                .header(HttpHeaders.AUTHORIZATION, bearer(me))).andExpect(status().isNotFound()));
        long hidden = statementsDuring(() -> mockMvc.perform(detailAs(me, blocked))
                .andExpect(status().isNotFound()));

        assertThat(unknown).isEqualTo(BASELINE_NOT_FOUND_STATEMENTS);
        assertThat(hidden).isEqualTo(BASELINE_NOT_FOUND_STATEMENTS);
    }

    @Test
    @DisplayName("LD-44 상세가 실제로 낸 찜 수 COUNT 쿼리를 EXPLAIN하면 idx_listing_favorites_listing을 타고 전체 스캔하지 않는다")
    void favoriteCountUsesListingIndex() throws Exception {
        Listing target = listingOf(user());
        Listing other = listingOf(user());
        for (long userId = 3_000_000L; userId < 3_000_020L; userId++) {
            favorite(userId, target);
            favorite(userId, other);
        }

        RecordingStatementInspector.clear();
        mockMvc.perform(detail(target)).andExpect(jsonPath("$.data.favoriteCount").value(20));
        // 손으로 쓴 SQL이 아니라 Hibernate가 countByListingId로 실제 내보낸 문장을 그대로 EXPLAIN한다.
        List<String> countSqls = RecordingStatementInspector.recorded().stream()
                .filter(sql -> sql.toLowerCase().contains("listing_favorites") && sql.toLowerCase().contains("count("))
                .toList();
        assertThat(countSqls).as("상세 한 번에 찜 수 COUNT 쿼리는 1개").hasSize(1);
        String countSql = countSqls.get(0);
        assertThat(countSql.chars().filter(c -> c == '?').count()).as("바인드 자리는 listing_id 하나: %s", countSql)
                .isEqualTo(1);

        List<Map<String, Object>> plan = jdbc.queryForList("EXPLAIN " + countSql, target.getId());

        assertThat(plan).as("EXPLAIN %s", countSql)
                .anyMatch(row -> "idx_listing_favorites_listing".equals(row.get("key")))
                .noneMatch(row -> "ALL".equals(row.get("type")));
    }

    @Test
    @DisplayName("LD-35 찜 수 컬럼을 만들지 않는다 — listings 테이블에 favorite 컬럼이 없다 (Q4 A')")
    void noDenormalizedColumn() {
        Long columns = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'listings' AND column_name LIKE '%favorite%'",
                Long.class);

        assertThat(columns).isZero();
    }

    // ── 개인정보·캐시·회귀 ─────────────────────────────────

    @Test
    @DisplayName("LD-37 상세 조회 중 로그에 판매자 email과 토큰 원문이 남지 않는다")
    void logsDoNotLeakSellerEmailOrToken(CapturedOutput output) throws Exception {
        User me = user();
        User seller = user();
        Listing listing = listingOf(seller);
        String authorization = bearer(me);
        int before = output.getOut().length();

        mockMvc.perform(detail(listing).header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.seller.publicId").value(seller.getPublicId()));

        String duringRequest = output.getOut().substring(before);
        // 판매자 조회가 실제로 나가 로그가 찍혔는지 먼저 확인한다(local 프로파일은 SQL을 debug로 남긴다).
        assertThat(duringRequest).containsIgnoringCase("users");
        assertThat(duringRequest).doesNotContain(seller.getEmail());
        assertThat(duringRequest).doesNotContain(authorization.substring("Bearer ".length()));
    }

    @Test
    @DisplayName("LD-38 상세 응답(비로그인·로그인 모두)의 Cache-Control에 no-store가 들어 있다")
    void responseIsNotCached() throws Exception {
        User me = user();
        Listing listing = listingOf(user());

        // 회귀 고정: 지금은 Spring Security 기본 헤더가 no-store를 붙인다. favorited가 사람마다 달라
        // 이 헤더가 빠지면 공유 캐시가 남의 favorited를 돌려줄 수 있다. 헤더 값만 보고 캐시 동작은 보지 않는다.
        mockMvc.perform(detail(listing))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
        mockMvc.perform(detailAs(me, listing))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
    }

    @Test
    @DisplayName("LD-39 기존 11개 필드의 이름·값은 그대로이고 새 필드 3개만 늘었다")
    void existingFieldsAreUnchanged() throws Exception {
        Listing listing = listingOf(user());
        Listing saved = listingRepository.findById(listing.getId()).orElseThrow();

        MvcResult result = mockMvc.perform(detail(listing))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publicId").value(listing.getPublicId()))
                .andExpect(jsonPath("$.data.title").value("아이폰"))
                .andExpect(jsonPath("$.data.description").value("설명"))
                .andExpect(jsonPath("$.data.price").value(950_000))
                .andExpect(jsonPath("$.data.category.code").value("DIGITAL_PHONE"))
                .andExpect(jsonPath("$.data.category.parentName").value("디지털기기"))
                .andExpect(jsonPath("$.data.itemCondition").value("USED"))
                .andExpect(jsonPath("$.data.region.sido").value("서울특별시"))
                .andExpect(jsonPath("$.data.region.sigungu").value("강남구"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.viewCount").value(0))
                .andExpect(jsonPath("$.data.version").value(saved.getVersion()))
                .andReturn();

        Map<String, Object> data = dataOf(result);
        assertThat(Instant.parse((String) data.get("createdAt"))).isEqualTo(saved.getCreatedAt());
        assertThat(data.keySet()).containsExactlyInAnyOrderElementsOf(DETAIL_KEYS);
    }

    @Test
    @DisplayName("LD-42 비활성 카테고리의 매물도 열리고, viewCount는 이번 조회 직전 값이다")
    void retiredCategoryListingStillOpens() throws Exception {
        User seller = user();
        Listing listing = listingOf(seller.getId(), retiredCategory());
        sql("UPDATE listings SET view_count = 7 WHERE id = ?", listing.getId());

        mockMvc.perform(detail(listing))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.viewCount").value(7))
                .andExpect(jsonPath("$.data.seller.publicId").value(seller.getPublicId()));

        assertThat(viewCountOf(listing)).isEqualTo(8);
    }

    @Test
    @DisplayName("LD-43 목록 API 항목은 그대로 8개 키 — seller·favoriteCount·favorited가 새지 않는다")
    void listApiIsUnchanged() throws Exception {
        Listing listing = listingOf(user());
        // 이 테스트만 쓰는 시군구로 옮겨 다른 테스트가 만든 매물이 결과에 섞이지 않게 한다.
        String sigungu = "LD43-" + UUID.randomUUID().toString().substring(0, 8);
        sql("UPDATE listings SET region_sigungu = ? WHERE id = ?", sigungu, listing.getId());

        MvcResult result = mockMvc.perform(get("/api/listings")
                        .param("regionSido", "서울특별시").param("regionSigungu", sigungu))
                .andExpect(status().isOk()).andReturn();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) dataOf(result).get("items");
        assertThat(items).hasSize(1);
        Map<String, Object> mine = items.get(0);
        assertThat(mine).containsEntry("publicId", listing.getPublicId());
        assertThat(mine.keySet()).containsExactlyInAnyOrder(
                "publicId", "title", "price", "categoryCode", "regionSido", "regionSigungu", "status", "createdAt");
    }

    // ── 도우미 ─────────────────────────────────────────────

    /** 토큰을 무시하고 비로그인처럼 답했는지: 200, 확장 필드는 채워지고 favorited만 null. */
    private void expectAnonymous(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.seller.publicId").exists())
                .andReturn();

        assertThat(dataOf(result)).containsEntry("favorited", null);
    }

    private int viewCountOf(Listing listing) {
        return jdbc.queryForObject("SELECT view_count FROM listings WHERE id = ?", Integer.class, listing.getId());
    }

    private interface Request {
        void run() throws Exception;
    }

    /**
     * 요청 하나 동안 나간 JDBC 문장 수(Hibernate Statistics). 컨텍스트가 공유되므로 끝나면 통계를 다시 끈다.
     * 문장 수는 JPA를 거친 것만 센다 — 이 경로는 JdbcTemplate을 쓰지 않는다.
     */
    private long statementsDuring(Request request) throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        assertThat(statistics.isStatisticsEnabled()).as("Hibernate Statistics가 켜져 있어야 문장 수를 믿을 수 있다").isTrue();
        try {
            request.run();
            return statistics.getPrepareStatementCount();
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    /** 모든 작업을 같은 순간에 풀어 경합 창을 넓힌다(FavoriteConcurrencyTest와 같은 방식). 실패는 그대로 올린다. */
    private <T> List<T> runConcurrently(List<Callable<T>> actions) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(actions.size());
        ExecutorService pool = Executors.newFixedThreadPool(actions.size());
        try {
            List<Callable<T>> tasks = new ArrayList<>();
            for (Callable<T> action : actions) {
                tasks.add(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return action.call();
                });
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : pool.invokeAll(tasks, 30, TimeUnit.SECONDS)) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static JwtTokenProvider providerWith(String secret, Duration accessTtl) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(secret);
        properties.setAccessTtl(accessTtl);
        properties.setRefreshTtl(Duration.ofDays(3));
        return new JwtTokenProvider(properties);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
