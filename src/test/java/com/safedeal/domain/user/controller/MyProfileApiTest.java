package com.safedeal.domain.user.controller;

import com.safedeal.domain.user.entity.User;
import com.safedeal.global.security.JwtProperties;
import com.safedeal.global.security.JwtTokenProvider;
import com.safedeal.global.security.TokenBlacklist;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/users/me — 내 프로필 조회 (케이스 문서 .claude/notes/users-me-cases.md, ME-01~30).
 * 시큐리티 필터·컨트롤러·서비스·DB를 끝까지 통과시킨다. 인증은 실제 JWT(Bearer)로 채운다.
 */
@ExtendWith(OutputCaptureExtension.class)
class MyProfileApiTest extends MyProfileApiSupport {

    @Autowired PasswordEncoder passwordEncoder;
    @Autowired TokenBlacklist tokenBlacklist;
    @Autowired JwtProperties jwtProperties;
    @Autowired TransactionTemplate tx;

    // ── 정상 ──────────────────────────────────────────────

    @Test
    @DisplayName("ME-01 로그인으로 받은 access로 조회하면 200이고 값이 DB와 같다 (실제 JWT 흐름)")
    void returnsOwnProfileWithRealLogin() throws Exception {
        User me = user("본인확인", passwordEncoder.encode("password123"));
        sql("UPDATE users SET email_verified = true WHERE id = ?", me.getId());

        MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"password123\"}".formatted(me.getEmail())))
                .andExpect(status().isOk())
                .andReturn();
        String access = objectMapper.readTree(login.getResponse().getContentAsString())
                .path("data").path("accessToken").asText();

        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.publicId").value(me.getPublicId()))
                .andExpect(jsonPath("$.data.email").value(me.getEmail()))
                .andExpect(jsonPath("$.data.nickname").value("본인확인"))
                .andExpect(jsonPath("$.data.emailVerified").value(true))
                .andExpect(jsonPath("$.data.trustScore").value(50.0));
    }

    @Test
    @DisplayName("ME-02 ME-15 가입 직후(메일 미인증·초기 점수)도 200이고 emailVerified=false, trustScore=50.0 (Q6)")
    void unverifiedNewUserIsAllowed() throws Exception {
        User me = user();

        String body = mockMvc.perform(meAs(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.emailVerified").value(false))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"trustScore\":50.0");
    }

    @ParameterizedTest(name = "ME-03 ME-05 내부 {0}, reportedFlag={1} → {2}")
    @CsvSource({"0, true, 0.0", "300, false, 30.0", "500, false, 50.0", "1000, true, 100.0"})
    @DisplayName("ME-03 ME-05 trustScore는 TrustScore.display 표시값이고, 점수·reportedFlag와 무관하게 키 집합이 같다 (Q5)")
    void trustScoreIsDisplayValue(int internal, boolean reportedFlag, String expected) throws Exception {
        User me = user();
        sql("UPDATE users SET trust_score = ?, reported_flag = ? WHERE id = ?", internal, reportedFlag, me.getId());

        MvcResult result = mockMvc.perform(meAs(me)).andExpect(status().isOk()).andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("\"trustScore\":" + expected);
        assertThat(dataOf(result).keySet()).containsExactlyInAnyOrderElementsOf(PROFILE_KEYS);
    }

    @Test
    @DisplayName("ME-04 createdAt은 ISO-8601 UTC(Z) 문자열이고 DB에 저장된 시각과 같다")
    void createdAtIsIsoUtc() throws Exception {
        User me = user();

        MvcResult result = mockMvc.perform(meAs(me)).andExpect(status().isOk()).andReturn();

        String createdAt = (String) dataOf(result).get("createdAt");
        assertThat(createdAt).endsWith("Z");
        assertThat(Instant.parse(createdAt)).isEqualTo(reload(me).getCreatedAt());
    }

    @Test
    @DisplayName("ME-07 20자 닉네임(한글·특수문자)은 원문 그대로 나간다")
    void nicknameIsReturnedVerbatim() throws Exception {
        String nickname = "가나다라마바사아자차<>&\"'!@#$%";
        assertThat(nickname).hasSize(20);
        User me = user(nickname, "hash");

        MvcResult result = mockMvc.perform(meAs(me)).andExpect(status().isOk()).andReturn();

        assertThat(dataOf(result).get("nickname")).isEqualTo(nickname);
    }

    @Test
    @DisplayName("ME-08 OAuth 계정(비밀번호 없음)도 200이고 비밀번호 관련 키가 없다")
    void oauthOnlyAccount() throws Exception {
        User me = user();
        sql("UPDATE users SET password_hash = NULL WHERE id = ?", me.getId());

        MvcResult result = mockMvc.perform(meAs(me)).andExpect(status().isOk()).andReturn();

        assertThat(dataOf(result).keySet()).containsExactlyInAnyOrderElementsOf(PROFILE_KEYS);
    }

    // ── 인증·권한 ──────────────────────────────────────────

    @Test
    @DisplayName("ME-09 토큰 없이 조회하면 401 C005")
    void withoutTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get(ME))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("C005"));
    }

    @Test
    @DisplayName("ME-10 만료된 토큰은 401 C005")
    void expiredTokenIsUnauthorized() throws Exception {
        User me = user();
        String expired = providerWith(jwtProperties.getSecret(), Duration.ofMinutes(-1))
                .issueAccess(me.getId(), "USER").value();

        expectUnauthorized("Bearer " + expired);
    }

    @Test
    @DisplayName("ME-10 다른 키로 서명된 토큰은 401 C005")
    void foreignSignatureIsUnauthorized() throws Exception {
        User me = user();
        String forged = providerWith("another-secret-key-for-forgery-test-32bytes!", Duration.ofMinutes(30))
                .issueAccess(me.getId(), "USER").value();

        expectUnauthorized("Bearer " + forged);
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @ValueSource(strings = {"Bearer not-a-jwt", "Bearer ", "Token abc.def.ghi"})
    @DisplayName("ME-10 형식이 틀린 Authorization은 401 C005")
    void malformedTokenIsUnauthorized(String authorization) throws Exception {
        expectUnauthorized(authorization);
    }

    @Test
    @DisplayName("ME-11 로그아웃(블랙리스트)된 토큰은 401 C005")
    void blacklistedTokenIsUnauthorized() throws Exception {
        User me = user();
        JwtTokenProvider.IssuedToken token = tokenProvider.issueAccess(me.getId(), "USER");
        tokenBlacklist.add(token.jti(), token.expiresAt());

        expectUnauthorized("Bearer " + token.value());
    }

    @Test
    @DisplayName("ME-12 유효한 토큰인데 계정이 없으면 401 AUTH004 (Q2)")
    void missingAccountIsInvalidToken() throws Exception {
        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, bearer(Long.MAX_VALUE, "USER")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH004"));
    }

    @Test
    @DisplayName("ME-12 유효한 토큰인데 탈퇴(deletedAt)한 계정이면 401 AUTH004 (Q2)")
    void deletedAccountIsInvalidToken() throws Exception {
        User me = user();
        sql("UPDATE users SET deleted_at = NOW(6) WHERE id = ?", me.getId());

        mockMvc.perform(meAs(me))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH004"));
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @ValueSource(strings = {"SUSPENDED", "BANNED"})
    @DisplayName("ME-13 ME-14 정지(SUSPENDED)·영구정지(BANNED) 계정은 403 AUTH005 (Q4)")
    void restrictedAccountIsForbidden(String status) throws Exception {
        User me = user();
        sql("UPDATE users SET status = ? WHERE id = ?", status, me.getId());

        mockMvc.perform(meAs(me))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AUTH005"));
    }

    @Test
    @DisplayName("ME-16 쿼리·헤더로 남의 id를 줘도 항상 토큰 주인의 정보를 준다")
    void ignoresOtherUserIdentifiers() throws Exception {
        User me = user();
        User other = user();

        mockMvc.perform(meAs(me)
                        .param("userId", String.valueOf(other.getId()))
                        .param("publicId", other.getPublicId())
                        .header("X-Dev-User-Id", String.valueOf(other.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publicId").value(me.getPublicId()))
                .andExpect(jsonPath("$.data.email").value(me.getEmail()));
    }

    @Test
    @DisplayName("ME-17 ADMIN도 200이고 본인 정보를 준다")
    void adminGetsOwnProfile() throws Exception {
        User admin = user();
        sql("UPDATE users SET role = 'ADMIN' WHERE id = ?", admin.getId());

        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, bearer(admin.getId(), "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publicId").value(admin.getPublicId()));
    }

    // ── 응답 화이트리스트·헤더 ─────────────────────────────

    @Test
    @DisplayName("ME-18 ME-06 ME-28 data 키는 합의한 6개와 정확히 같다(id·passwordHash·role·status·nicknameChangeableAt 등 없음)")
    void responseKeysAreWhitelisted() throws Exception {
        User me = user();

        MvcResult result = mockMvc.perform(meAs(me)).andExpect(status().isOk()).andReturn();

        assertThat(dataOf(result).keySet()).containsExactlyInAnyOrderElementsOf(PROFILE_KEYS);
        // 응답 최상위도 성공 형식(success·data)만 있어야 한다.
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("success").asBoolean()).isTrue();
        assertThat(body.has("error")).isFalse();
    }

    @Test
    @DisplayName("ME-20 조회 중 로그에 email·토큰 원문이 남지 않는다")
    void logsDoNotLeakEmailOrToken(CapturedOutput output) throws Exception {
        User me = user();
        String authorization = bearer(me);
        int before = output.getOut().length();

        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, authorization)).andExpect(status().isOk());

        String duringRequest = output.getOut().substring(before);
        // 캡처가 실제로 동작하는지(local 프로파일은 SQL을 debug로 남긴다) 먼저 확인한다 — 빈 캡처로 통과하지 않도록.
        assertThat(duringRequest).containsIgnoringCase("users");
        assertThat(duringRequest).doesNotContain(me.getEmail());
        assertThat(duringRequest).doesNotContain(authorization.substring("Bearer ".length()));
    }

    @Test
    @DisplayName("ME-21 200 응답의 Cache-Control에 no-store가 있다 (Q8)")
    void responseIsNotCached() throws Exception {
        User me = user();

        mockMvc.perform(meAs(me))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
    }

    // ── 중복·동시성 ────────────────────────────────────────

    @Test
    @DisplayName("ME-25 연속 요청은 매번 같은 200이고 사용자 행을 바꾸지 않는다")
    void repeatedRequestsAreIdempotent() throws Exception {
        User me = user();
        User before = reload(me);

        String first = mockMvc.perform(meAs(me)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(meAs(me)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        User after = reload(me);
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
        assertThat(after.getLastLoginAt()).isEqualTo(before.getLastLoginAt());
    }

    @Test
    @DisplayName("ME-26 신뢰도 갱신이 사용자 행을 잠그고 있어도 조회는 막히지 않는다")
    void notBlockedByTrustScoreRowLock() throws Exception {
        User me = user();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
                userRepository.findForTrustScoreUpdate(me.getId()).orElseThrow();
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).as("잠금 획득").isTrue();

            Future<Integer> read = pool.submit(() -> mockMvc.perform(meAs(me)).andReturn().getResponse().getStatus());

            // 잠금 읽기를 쓰면 이 조회가 잠금이 풀릴 때까지(최대 innodb_lock_wait_timeout) 기다린다.
            assertThat(read.get(5, TimeUnit.SECONDS)).isEqualTo(200);
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    // ── 회귀 ───────────────────────────────────────────────

    @Test
    @DisplayName("ME-30 /api/users/me/favorites는 /me 추가 후에도 그대로 찜 목록 응답을 준다")
    void favoritesPathIsNotShadowed() throws Exception {
        User me = user();

        mockMvc.perform(get(ME + "/favorites").header(HttpHeaders.AUTHORIZATION, bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    // ── 도우미 ─────────────────────────────────────────────

    private void expectUnauthorized(String authorization) throws Exception {
        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("C005"));
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
