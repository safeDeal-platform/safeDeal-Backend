package com.safedeal.domain.listing.controller;

import com.safedeal.domain.listing.entity.Category;
import com.safedeal.domain.listing.entity.ItemCondition;
import com.safedeal.domain.listing.entity.Listing;
import com.safedeal.domain.listing.repository.CategoryRepository;
import com.safedeal.domain.listing.repository.ListingRepository;
import com.safedeal.domain.user.entity.User;
import com.safedeal.domain.user.repository.UserRepository;
import com.safedeal.global.security.JwtTokenProvider;
import com.safedeal.global.util.PublicId;
import com.safedeal.testsupport.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * GET /api/listings/{publicId} 확장(케이스 문서 .claude/notes/listing-detail-expand-cases.md, LD-01~45)
 * 통합 테스트의 공통 준비물. 시큐리티 필터·컨트롤러·서비스·실제 MySQL을 끝까지 통과시킨다.
 *
 * <p>{@code @Transactional}을 붙이지 않는다 — 동시성·잠금 테스트가 커밋된 데이터를 봐야 한다.
 * 만든 사용자·매물·찜은 {@link #cleanUp()}에서 직접 지운다.
 */
@AutoConfigureMockMvc
abstract class ListingDetailApiSupport extends IntegrationTestSupport {

    /** 기존 상세 11개 필드 + 이번에 붙는 3개(seller, favoriteCount, favorited). */
    static final Set<String> DETAIL_KEYS = Set.of(
            "publicId", "title", "description", "price", "category", "itemCondition", "region",
            "status", "viewCount", "createdAt", "version",
            "seller", "favoriteCount", "favorited");

    /** 합의한 seller 키 (LD-36). email·id·status 등은 나가지 않는다. */
    static final Set<String> SELLER_KEYS = Set.of("publicId", "nickname", "trustScore");

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired ListingRepository listingRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired JwtTokenProvider tokenProvider;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<Long> createdListingIds = new ArrayList<>();
    private final List<Long> createdCategoryIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long listingId : createdListingIds) {
            jdbc.update("DELETE FROM listing_favorites WHERE listing_id = ?", listingId);
        }
        listingRepository.deleteAllByIdInBatch(createdListingIds);
        categoryRepository.deleteAllByIdInBatch(createdCategoryIds);
        userRepository.deleteAllByIdInBatch(createdUserIds);
        createdListingIds.clear();
        createdCategoryIds.clear();
        createdUserIds.clear();
    }

    /** 비활성으로 내려간 중분류. 시드 분류를 내리면 다른 테스트에 새므로 자기 것을 만든다. */
    Category retiredCategory() {
        Category digital = categoryRepository.findByCode("DIGITAL").orElseThrow();
        Category retired = Category.child("DIGITAL_LD" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(),
                "내려간 분류", digital, 99);
        retired.deactivate();
        retired = categoryRepository.saveAndFlush(retired);
        createdCategoryIds.add(retired.getId());
        return retired;
    }

    // ── 준비 데이터 ────────────────────────────────────────

    User user() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = userRepository.saveAndFlush(
                User.createLocal("ld-" + suffix + "@safedeal.test", "hash", "ld" + suffix));
        createdUserIds.add(user.getId());
        return user;
    }

    /** 판매자의 ACTIVE 매물 (검증 구간 꺼짐 → 바로 ACTIVE). */
    Listing listingOf(User seller) {
        return listingOf(seller.getId(), categoryRepository.findByCode("DIGITAL_PHONE").orElseThrow());
    }

    Listing listingOf(Long sellerId, Category category) {
        Listing listing = listingRepository.saveAndFlush(Listing.register(PublicId.generate(), sellerId,
                "아이폰", "설명", 950_000, category, ItemCondition.USED, "서울특별시", "강남구", false));
        createdListingIds.add(listing.getId());
        return listing;
    }

    /** 찜 한 건. listing_favorites.user_id에는 FK가 없어 실제 계정이 아니어도 된다. */
    void favorite(Long userId, Listing listing) {
        jdbc.update("INSERT INTO listing_favorites (user_id, listing_id, notify_base_price, created_at, updated_at) "
                + "VALUES (?, ?, ?, NOW(6), NOW(6))", userId, listing.getId(), listing.getPrice());
    }

    /** 계정 상태·점수·탈퇴는 다른 도메인이 바꾸는 값이라 setter가 없다 — SQL로 직접 바꾼다. */
    void sql(String update, Object... args) {
        jdbc.update(update, args);
    }

    long favoriteRows(Listing listing) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM listing_favorites WHERE listing_id = ?",
                Long.class, listing.getId());
    }

    // ── 요청 ──────────────────────────────────────────────

    String bearer(User user) {
        return bearer(user.getId(), user.getRole().name());
    }

    String bearer(Long userId, String role) {
        return "Bearer " + tokenProvider.issueAccess(userId, role).value();
    }

    MockHttpServletRequestBuilder detail(Listing listing) {
        return get("/api/listings/" + listing.getPublicId());
    }

    MockHttpServletRequestBuilder detailAs(User viewer, Listing listing) {
        return detail(listing).header(HttpHeaders.AUTHORIZATION, bearer(viewer));
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> dataOf(MvcResult result) throws Exception {
        Map<String, Object> body = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        return (Map<String, Object>) body.get("data");
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> sellerOf(MvcResult result) throws Exception {
        return (Map<String, Object>) dataOf(result).get("seller");
    }
}
