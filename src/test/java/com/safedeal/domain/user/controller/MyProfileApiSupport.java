package com.safedeal.domain.user.controller;

import com.safedeal.domain.user.entity.User;
import com.safedeal.domain.user.repository.UserRepository;
import com.safedeal.global.security.JwtTokenProvider;
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
 * GET /api/users/me 통합 테스트(실제 MySQL·Redis, 시큐리티 필터 포함)의 공통 준비물.
 *
 * <p>{@code @Transactional}을 붙이지 않는다 — 로그인 흐름과 잠금 동시성 테스트가 커밋된 데이터를 봐야 한다.
 * 대신 만든 사용자를 {@link #cleanUp()}에서 직접 지운다.
 */
@AutoConfigureMockMvc
abstract class MyProfileApiSupport extends IntegrationTestSupport {

    static final String ME = "/api/users/me";

    /** 합의된 응답 data 키 (Q3 nicknameChangeableAt 제외, Q4 status 없음, Q7 role 제외). */
    static final Set<String> PROFILE_KEYS =
            Set.of("publicId", "email", "nickname", "emailVerified", "trustScore", "createdAt");

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired JwtTokenProvider tokenProvider;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    private final List<Long> createdUserIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        userRepository.deleteAllByIdInBatch(createdUserIds);
        createdUserIds.clear();
    }

    User user() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return user("me" + suffix, "hash");
    }

    User user(String nickname, String passwordHash) {
        String email = "me-" + UUID.randomUUID().toString().substring(0, 8) + "@safedeal.test";
        User user = userRepository.saveAndFlush(User.createLocal(email, passwordHash, nickname));
        createdUserIds.add(user.getId());
        return user;
    }

    /** DB에 실제로 저장된 값(정밀도 포함)으로 다시 읽는다 — 응답과 비교하는 기준값. */
    User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }

    /** 계정 상태·역할·점수는 다른 도메인이 바꾸는 값이라 엔티티에 setter가 없다 — SQL로 직접 바꾼다. */
    void sql(String update, Object... args) {
        jdbc.update(update, args);
    }

    String bearer(User user) {
        return bearer(user.getId(), user.getRole().name());
    }

    String bearer(Long userId, String role) {
        return "Bearer " + tokenProvider.issueAccess(userId, role).value();
    }

    MockHttpServletRequestBuilder meAs(User user) {
        return get(ME).header(HttpHeaders.AUTHORIZATION, bearer(user));
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> dataOf(MvcResult result) throws Exception {
        Map<String, Object> body = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        return (Map<String, Object>) body.get("data");
    }
}
