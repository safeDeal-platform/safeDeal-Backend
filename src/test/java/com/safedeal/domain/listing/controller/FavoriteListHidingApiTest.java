package com.safedeal.domain.listing.controller;

import com.safedeal.domain.listing.entity.Listing;
import com.safedeal.domain.user.entity.User;
import jakarta.persistence.EntityManagerFactory;
import org.assertj.core.groups.Tuple;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/users/me/favorites — 제재·삭제 매물 가리기 (케이스 문서 .claude/notes/favorite-hide-sanctioned-cases.md).
 * 실제 JWT·시큐리티 필터·실제 MySQL을 끝까지 통과시킨다. 가린 항목은 title·price·notifyBasePrice 키가
 * null로 남고(Q5) publicId·status·favoritedAt은 그대로다.
 */
class FavoriteListHidingApiTest extends ListingDetailApiSupport {

    /**
     * 가리기 구현 전(dev 884eb29) 찜 목록 한 번에 나가는 JDBC 문장 수 — 2026-11-19 Hibernate Statistics로 측정.
     * [findPage 1(찜 + 매물 JOIN FETCH)]뿐이다. 인증은 JWT 서명 검증·Redis 블랙리스트라 DB를 타지 않는다.
     */
    private static final long BASELINE_STATEMENTS = 1;

    private static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");

    @Autowired EntityManagerFactory entityManagerFactory;

    // ── 가림 ──────────────────────────────────────────────

    @Test
    @DisplayName("FH-03 FH-11 BLOCKED 매물은 title·price·notifyBasePrice가 null, publicId·status·favoritedAt은 그대로")
    void blockedItemIsHidden() throws Exception {
        User me = user();
        Listing listing = listingOf(user());
        favoriteAt(me, listing, T0);
        sql("UPDATE listings SET status = 'BLOCKED' WHERE id = ?", listing.getId());

        Map<String, Object> item = itemsOf(myFavorites(me, "")).get(0);

        assertThat(item).containsEntry("favoritedAt", "2026-09-01T00:00:00Z");
        assertThat(listingOf(item)).containsEntry("publicId", listing.getPublicId())
                .containsEntry("status", "BLOCKED")
                .containsEntry("title", null)
                .containsEntry("price", null);
        assertThat(item).containsEntry("notifyBasePrice", null);
    }

    @Test
    @DisplayName("FH-04 FH-11 판매자가 DELETE /api/listings/{publicId}로 지운 매물도 title·price·notifyBasePrice가 null, status=DELETED")
    void sellerDeletedItemIsHidden() throws Exception {
        User me = user();
        User seller = user();
        Listing listing = listingOf(seller);
        favoriteAt(me, listing, T0);
        mockMvc.perform(delete("/api/listings/" + listing.getPublicId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isOk());

        Map<String, Object> item = itemsOf(myFavorites(me, "")).get(0);

        assertThat(item).containsEntry("favoritedAt", "2026-09-01T00:00:00Z");
        assertThat(listingOf(item)).containsEntry("publicId", listing.getPublicId())
                .containsEntry("status", "DELETED")
                .containsEntry("title", null)
                .containsEntry("price", null);
        assertThat(item).containsEntry("notifyBasePrice", null);
    }

    @Test
    @DisplayName("FH-07 가려서 조회한 뒤에도 DB의 title·price·notify_base_price는 그대로다")
    void hidingDoesNotTouchStoredValues() throws Exception {
        User me = user();
        Listing listing = listingOf(user());
        favoriteAt(me, listing, T0);
        sql("UPDATE listings SET status = 'BLOCKED' WHERE id = ?", listing.getId());

        Map<String, Object> item = itemsOf(myFavorites(me, "")).get(0);

        assertThat(listingOf(item)).containsEntry("title", null);
        assertThat(jdbc.queryForMap(
                "SELECT l.title, l.price, f.notify_base_price FROM listings l "
                        + "JOIN listing_favorites f ON f.listing_id = l.id WHERE l.id = ?", listing.getId()))
                .containsEntry("title", "아이폰")
                .containsEntry("price", 950_000)
                .containsEntry("notify_base_price", 950_000);
    }

    @Test
    @DisplayName("FH-09 가린 항목도 JSON 키는 남는다 — listing{publicId,title,price,status} + notifyBasePrice·favoritedAt, 값만 null")
    void hiddenItemKeepsKeysWithNull() throws Exception {
        User me = user();
        Listing listing = listingOf(user());
        favoriteAt(me, listing, T0);
        sql("UPDATE listings SET status = 'BLOCKED' WHERE id = ?", listing.getId());

        Map<String, Object> item = itemsOf(myFavorites(me, "")).get(0);

        assertThat(item).containsOnlyKeys("listing", "notifyBasePrice", "favoritedAt");
        assertThat(listingOf(item)).containsOnlyKeys("publicId", "title", "price", "status");
        assertThat(listingOf(item)).containsEntry("title", null).containsEntry("price", null);
        assertThat(item).containsEntry("notifyBasePrice", null);
    }

    @Test
    @DisplayName("FH-10 최저가 1000원 ACTIVE 매물의 price·notifyBasePrice는 JSON 숫자 1000이다")
    void minimumPriceIsJsonNumber() throws Exception {
        User me = user();
        Listing listing = listingOf(user());
        sql("UPDATE listings SET price = 1000 WHERE id = ?", listing.getId());
        sql("INSERT INTO listing_favorites (user_id, listing_id, notify_base_price, created_at, updated_at) "
                + "VALUES (?, ?, 1000, ?, ?)", me.getId(), listing.getId(), utc(T0), utc(T0));

        Map<String, Object> item = itemsOf(myFavorites(me, "")).get(0);

        assertThat(listingOf(item).get("price")).isInstanceOf(Integer.class).isEqualTo(1_000);
        assertThat(item.get("notifyBasePrice")).isInstanceOf(Integer.class).isEqualTo(1_000);
    }

    // ── 페이지·커서 ───────────────────────────────────────

    @Test
    @DisplayName("FH-12 ACTIVE·SOLD·BLOCKED·DELETED가 섞여도 개수·순서·hasNext는 그대로이고 BLOCKED·DELETED만 가린다")
    void mixedPageKeepsCountOrderAndHasNext() throws Exception {
        User me = user();
        User seller = user();
        Listing active = listingOf(seller);
        Listing sold = listingOf(seller);
        Listing blocked = listingOf(seller);
        Listing deleted = listingOf(seller);
        favoriteAt(me, active, T0.plusSeconds(4));
        favoriteAt(me, sold, T0.plusSeconds(3));
        favoriteAt(me, blocked, T0.plusSeconds(2));
        favoriteAt(me, deleted, T0.plusSeconds(1));
        sql("UPDATE listings SET status = 'SOLD' WHERE id = ?", sold.getId());
        sql("UPDATE listings SET status = 'BLOCKED' WHERE id = ?", blocked.getId());
        sql("UPDATE listings SET status = 'DELETED', deleted_at = NOW(6) WHERE id = ?", deleted.getId());

        Map<String, Object> first = dataOf(myFavorites(me, "?size=3"));
        Map<String, Object> second = dataOf(myFavorites(me, "?size=3&cursor=" + first.get("nextCursor")));

        assertThat(first.get("hasNext")).isEqualTo(true);
        assertThat(second.get("hasNext")).isEqualTo(false);
        assertThat(rowsOf(first)).containsExactly(
                tuple(active.getPublicId(), "ACTIVE", "아이폰", 950_000),
                tuple(sold.getPublicId(), "SOLD", "아이폰", 950_000),
                tuple(blocked.getPublicId(), "BLOCKED", null, null));
        assertThat(rowsOf(second)).containsExactly(
                tuple(deleted.getPublicId(), "DELETED", null, null));
    }

    @Test
    @DisplayName("FH-13 첫 페이지 마지막 항목이 BLOCKED여도 다음 페이지가 그 뒤에서 이어져 누락·중복이 없다")
    void cursorAtBlockedItemHasNoGapOrDuplicate() throws Exception {
        User me = user();
        User seller = user();
        Listing newest = listingOf(seller);
        Listing blocked = listingOf(seller);
        Listing oldest = listingOf(seller);
        favoriteAt(me, newest, T0.plusSeconds(3));
        favoriteAt(me, blocked, T0.plusSeconds(2));
        favoriteAt(me, oldest, T0.plusSeconds(1));
        sql("UPDATE listings SET status = 'BLOCKED' WHERE id = ?", blocked.getId());

        Map<String, Object> first = dataOf(myFavorites(me, "?size=2"));
        assertThat(first.get("hasNext")).isEqualTo(true);
        Map<String, Object> second = dataOf(myFavorites(me, "?size=2&cursor=" + first.get("nextCursor")));

        assertThat(rowsOf(first)).containsExactly(
                tuple(newest.getPublicId(), "ACTIVE", "아이폰", 950_000),
                tuple(blocked.getPublicId(), "BLOCKED", null, null));
        assertThat(rowsOf(second)).containsExactly(
                tuple(oldest.getPublicId(), "ACTIVE", "아이폰", 950_000));
        assertThat(second.get("hasNext")).isEqualTo(false);
    }

    @Test
    @DisplayName("FH-14 찜이 0건이면 items=[], hasNext=false, nextCursor=null")
    void emptyFavorites() throws Exception {
        // 회귀 방지용 — 가리기와 무관하게 지금 코드로도 통과하도록 만든 테스트다(설계상 Green).
        Map<String, Object> data = dataOf(myFavorites(user(), ""));

        assertThat(itemsOf(data)).isEmpty();
        assertThat(data.get("hasNext")).isEqualTo(false);
        assertThat(data).containsEntry("nextCursor", null);
    }

    // ── 성능 (Q9) ─────────────────────────────────────────

    @Test
    @DisplayName("FH-19 가린 항목이 섞인 찜 목록도 JDBC 문장 수는 가리기 전과 같다 (findPage 1번, 항목별 추가 쿼리 없음)")
    void statementCountUnchanged() throws Exception {
        User me = user();
        User seller = user();
        Listing active = listingOf(seller);
        Listing blocked = listingOf(seller);
        Listing deleted = listingOf(seller);
        favoriteAt(me, active, T0.plusSeconds(3));
        favoriteAt(me, blocked, T0.plusSeconds(2));
        favoriteAt(me, deleted, T0.plusSeconds(1));
        sql("UPDATE listings SET status = 'BLOCKED' WHERE id = ?", blocked.getId());
        sql("UPDATE listings SET status = 'DELETED', deleted_at = NOW(6) WHERE id = ?", deleted.getId());

        long statements = statementsDuring(() -> assertThat(itemsOf(myFavorites(me, ""))).hasSize(3));

        assertThat(statements)
                .as("찜 목록 문장 수: 가리기 전 %d(findPage) 미만이면 측정이 죽은 것이고, 넘으면 항목별 쿼리가 생긴 것",
                        BASELINE_STATEMENTS)
                .isGreaterThanOrEqualTo(BASELINE_STATEMENTS)
                .isLessThanOrEqualTo(BASELINE_STATEMENTS);
    }

    // ── 도우미 ─────────────────────────────────────────────

    /** 찜한 시각을 직접 정한다 — 순서·커서 테스트가 NOW(6) 동률에 흔들리지 않게. */
    private void favoriteAt(User user, Listing listing, Instant at) {
        sql("INSERT INTO listing_favorites (user_id, listing_id, notify_base_price, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?)", user.getId(), listing.getId(), listing.getPrice(),
                utc(at), utc(at));
    }

    /** Hibernate가 hibernate.jdbc.time_zone=UTC로 저장하므로 직접 넣는 시각도 UTC 벽시계 문자열로 맞춘다. */
    private static String utc(Instant at) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS").withZone(ZoneOffset.UTC).format(at);
    }

    private MvcResult myFavorites(User user, String query) throws Exception {
        return mockMvc.perform(get("/api/users/me/favorites" + query)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andReturn();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> itemsOf(MvcResult result) throws Exception {
        return (List<Map<String, Object>>) dataOf(result).get("items");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> itemsOf(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("items");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> listingOf(Map<String, Object> item) {
        return (Map<String, Object>) item.get("listing");
    }

    /** 항목마다 (publicId, status, title, price). */
    private static List<Tuple> rowsOf(Map<String, Object> data) {
        return itemsOf(data).stream()
                .map(item -> listingOf(item))
                .map(l -> tuple(l.get("publicId"), l.get("status"), l.get("title"), l.get("price")))
                .toList();
    }

    private interface Request {
        void run() throws Exception;
    }

    /** 요청 하나 동안 나간 JDBC 문장 수(Hibernate Statistics). 컨텍스트가 공유되므로 끝나면 원래 켜짐 여부로 되돌린다. */
    private long statementsDuring(Request request) throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean before = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        assertThat(statistics.isStatisticsEnabled()).as("Hibernate Statistics가 켜져 있어야 문장 수를 믿을 수 있다").isTrue();
        try {
            request.run();
            return statistics.getPrepareStatementCount();
        } finally {
            statistics.setStatisticsEnabled(before);
        }
    }
}
