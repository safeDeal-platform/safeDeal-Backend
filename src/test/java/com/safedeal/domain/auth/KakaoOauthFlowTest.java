package com.safedeal.domain.auth;

import com.safedeal.domain.auth.client.KakaoOauthProperties;
import com.safedeal.domain.auth.entity.OauthProvider;
import com.safedeal.domain.auth.exception.AuthErrorCode;
import com.safedeal.domain.auth.repository.OauthAccountRepository;
import com.safedeal.domain.auth.service.OauthCommandService;
import com.safedeal.domain.user.entity.User;
import com.safedeal.domain.user.repository.UserRepository;
import com.safedeal.global.exception.BusinessException;
import com.safedeal.global.exception.CommonErrorCode;
import com.safedeal.testsupport.FakeKakaoOauthClient;
import com.safedeal.testsupport.IntegrationTestSupport;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockCookie;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 카카오 로그인 (AUTH-5) — 시작 → 콜백 → (처음이면) 닉네임 가입.
 *
 * 카카오 서버는 {@link FakeKakaoOauthClient}로 바꿔 끼운다. 여기서 고정하는 것은 보안 가정이다:
 * state 위조·재사용 차단, 미인증 이메일 거부, 미인증 로컬 계정에 자동 연결하지 않는 것, 가입 티켓 1회 소진.
 *
 * {@code @Transactional}을 붙이지 않는다 — 콜백·가입은 커밋돼야 다음 요청이 보고, 동시성 테스트는
 * 별도 스레드라 롤백 대상도 아니다. 만든 행은 {@link #cleanUp()}에서 직접 지운다.
 */
@AutoConfigureMockMvc
class KakaoOauthFlowTest extends IntegrationTestSupport {

    private static final String START = "/api/auth/oauth/kakao";
    private static final String CALLBACK = "/api/auth/oauth/kakao/callback";
    private static final String SIGNUP = "/api/auth/oauth/kakao/signup";
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());

    @Autowired MockMvc mockMvc;
    @Autowired FakeKakaoOauthClient kakao;
    @Autowired OauthCommandService oauthCommandService;
    @Autowired OauthAccountRepository oauthAccountRepository;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired KakaoOauthProperties kakaoProperties;

    private final List<String> emails = new ArrayList<>();

    @BeforeEach
    void resetFake() {
        kakao.reset();
    }

    @AfterEach
    void cleanUp() {
        for (String email : emails) {
            List<Long> ids = jdbc.queryForList("SELECT id FROM users WHERE email = ?", Long.class, email);
            for (Long id : ids) {
                jdbc.update("DELETE FROM oauth_accounts WHERE user_id = ?", id);
                redisTemplate.delete("auth:refresh:v1:" + id);
            }
            jdbc.update("DELETE FROM users WHERE email = ?", email);
        }
        emails.clear();
    }

    // ── 준비물 ──

    private String newEmail() {
        String email = "kakao-" + SEQ.incrementAndGet() + "@safedeal.test";
        emails.add(email);
        return email;
    }

    private long newKakaoId() {
        return SEQ.incrementAndGet();
    }

    private static String nickname() {
        return "카카오" + (SEQ.incrementAndGet() % 1_000_000_000L);
    }

    private User localUser(String email, boolean verified) {
        User user = User.createLocal(email, passwordEncoder.encode("password123"), nickname());
        if (verified) {
            user.verifyEmail();
        }
        return userRepository.save(user);
    }

    /** 로그인 시작 — 브라우저가 받는 state(쿠키) 값을 돌려준다. */
    private String start() throws Exception {
        MvcResult result = mockMvc.perform(get(START)).andExpect(status().isFound()).andReturn();
        MockCookie cookie = (MockCookie) result.getResponse().getCookie("oauthState");
        assertThat(cookie).isNotNull();
        String location = result.getResponse().getHeader(HttpHeaders.LOCATION);
        assertThat(query(location).getFirst("state")).isEqualTo(cookie.getValue());
        return cookie.getValue();
    }

    private MvcResult callback(String code, String state, String stateCookie) throws Exception {
        MockHttpServletRequestBuilder request = get(CALLBACK);
        if (code != null) {
            request.param("code", code);
        }
        if (state != null) {
            request.param("state", state);
        }
        if (stateCookie != null) {
            request.cookie(new Cookie("oauthState", stateCookie));
        }
        return mockMvc.perform(request).andExpect(status().isFound()).andReturn();
    }

    /** 카카오에 code를 등록해 두고 정상 시작 → 콜백까지. */
    private MvcResult loginWith(String code) throws Exception {
        String state = start();
        return callback(code, state, state);
    }

    private static MultiValueMap<String, String> query(String location) {
        return UriComponentsBuilder.fromUriString(location).build().getQueryParams();
    }

    private void assertRedirect(MvcResult result, String key, String value) {
        String location = result.getResponse().getHeader(HttpHeaders.LOCATION);
        assertThat(location).startsWith(kakaoProperties.getFrontendCallbackUrl() + "?");
        // 결과 외의 값(code·state·이메일 등)이 프런트 주소에 새지 않는다.
        assertThat(query(location).toSingleValueMap()).containsExactly(java.util.Map.entry(key, value));
    }

    private static String signupTicketOf(MvcResult result) {
        MockCookie cookie = (MockCookie) result.getResponse().getCookie("oauthSignup");
        assertThat(cookie).isNotNull();
        assertOauthCookie(cookie);
        return cookie.getValue();
    }

    /** state·가입 티켓 쿠키 공통 속성 — JS로 못 읽고, https로만, 이 경로에만, Redis 보관 시간만큼만. */
    private static void assertOauthCookie(MockCookie cookie) {
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Lax");
        assertThat(cookie.getPath()).isEqualTo("/api/auth/oauth");
        assertThat(cookie.getMaxAge()).isEqualTo((int) OauthCommandService.COOKIE_TTL.toSeconds());
    }

    private MvcResult signup(String ticket, String nickname) throws Exception {
        MockHttpServletRequestBuilder request = post(SIGNUP)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"" + nickname + "\"}");
        if (ticket != null) {
            request.cookie(new Cookie("oauthSignup", ticket));
        }
        return mockMvc.perform(request).andReturn();
    }

    // ── state ──

    @Test
    @DisplayName("시작하면 카카오 동의 화면으로 보내고, 같은 state를 쿠키(경로 /api/auth/oauth, httpOnly)에도 심는다")
    void startRedirectsToKakaoWithStateCookie() throws Exception {
        MvcResult result = mockMvc.perform(get(START)).andExpect(status().isFound()).andReturn();

        String location = result.getResponse().getHeader(HttpHeaders.LOCATION);
        assertThat(location).startsWith("https://kauth.kakao.com/oauth/authorize?");
        MultiValueMap<String, String> params = query(location);
        assertThat(params.getFirst("response_type")).isEqualTo("code");
        assertThat(params.getFirst("client_id")).isEqualTo(kakaoProperties.getClientId());
        assertThat(params.getFirst("redirect_uri")).isEqualTo(kakaoProperties.getRedirectUri());

        MockCookie cookie = (MockCookie) result.getResponse().getCookie("oauthState");
        assertThat(cookie.getValue()).isEqualTo(params.getFirst("state"));
        assertOauthCookie(cookie);
    }

    @Test
    @DisplayName("state가 쿠키와 다르거나, 쿠키가 없거나, 아예 없으면 AUTH011이고 카카오를 부르지 않는다")
    void invalidStateIsRejectedBeforeCallingKakao() throws Exception {
        kakao.register("code-1", FakeKakaoOauthClient.verified(newKakaoId(), newEmail()));

        String state = start();
        assertRedirect(callback("code-1", state, "attacker-state"), "error", "AUTH011");
        assertRedirect(callback("code-1", state, null), "error", "AUTH011");
        assertRedirect(callback("code-1", null, null), "error", "AUTH011");

        assertThat(kakao.fetchCount()).isZero();
    }

    @Test
    @DisplayName("쿠키와 맞아도 서버가 발급하지 않은 state는 AUTH011이다 (공격자가 쿠키와 파라미터를 같이 지어낸 경우)")
    void forgedStateIsRejected() throws Exception {
        kakao.register("code-1", FakeKakaoOauthClient.verified(newKakaoId(), newEmail()));

        assertRedirect(callback("code-1", "made-up", "made-up"), "error", "AUTH011");
        assertThat(kakao.fetchCount()).isZero();
    }

    @Test
    @DisplayName("한 번 쓴 state는 다시 못 쓴다 — 같은 콜백 주소를 두 번 열면 두 번째는 AUTH011")
    void stateIsSingleUse() throws Exception {
        long kakaoId = newKakaoId();
        String email = newEmail();
        kakao.register("code-1", FakeKakaoOauthClient.verified(kakaoId, email));

        String state = start();
        assertRedirect(callback("code-1", state, state), "result", "signup");
        assertRedirect(callback("code-1", state, state), "error", "AUTH011");
        assertThat(kakao.fetchCount()).isOne();
    }

    @Test
    @DisplayName("콜백은 결과와 상관없이 state 쿠키를 지운다")
    void callbackClearsStateCookie() throws Exception {
        MvcResult result = callback("code-x", "made-up", "made-up");
        MockCookie cleared = (MockCookie) result.getResponse().getCookie("oauthState");
        assertThat(cleared.getMaxAge()).isZero();
    }

    // ── 카카오 쪽 결과 ──

    @Test
    @DisplayName("동의 화면에서 취소하면 AUTH013, 카카오 호출이 실패하면 AUTH012")
    void cancelAndProviderFailure() throws Exception {
        String state = start();
        MvcResult cancelled = mockMvc.perform(get(CALLBACK)
                        .param("error", "access_denied").param("state", state)
                        .cookie(new Cookie("oauthState", state)))
                .andExpect(status().isFound()).andReturn();
        assertRedirect(cancelled, "error", "AUTH013");

        assertRedirect(loginWith("unknown-code"), "error", "AUTH012");
    }

    @Test
    @DisplayName("카카오가 취소가 아닌 오류(server_error)를 보내거나 code 없이 오면 AUTH012 — 취소로 안내하지 않는다")
    void providerErrorIsNotCancel() throws Exception {
        String state = start();
        MvcResult serverError = mockMvc.perform(get(CALLBACK)
                        .param("error", "server_error").param("state", state)
                        .cookie(new Cookie("oauthState", state)))
                .andExpect(status().isFound()).andReturn();
        assertRedirect(serverError, "error", "AUTH012");

        String again = start();
        assertRedirect(callback(null, again, again), "error", "AUTH012");
        assertThat(kakao.fetchCount()).isZero();
    }

    @Test
    @DisplayName("예상 못 한 장애도 JSON 원문 대신 프런트로 보낸다(C007)")
    void unexpectedFailureStillRedirects() throws Exception {
        assertRedirect(loginWith(FakeKakaoOauthClient.BOOM), "error", "C007");
    }

    @Test
    @DisplayName("카카오 이메일이 미동의·무효·미검증이면 AUTH014이고 계정도 티켓도 만들지 않는다")
    void untrustedKakaoEmailIsRejected() throws Exception {
        String email = newEmail();
        kakao.register("needs", FakeKakaoOauthClient.account(newKakaoId(), true, null, null, null));
        kakao.register("invalid", FakeKakaoOauthClient.account(newKakaoId(), false, false, true, email));
        kakao.register("unverified", FakeKakaoOauthClient.account(newKakaoId(), false, true, false, email));

        for (String code : List.of("needs", "invalid", "unverified")) {
            MvcResult result = loginWith(code);
            assertRedirect(result, "error", "AUTH014");
            assertThat(result.getResponse().getCookie("oauthSignup")).isNull();
        }
        assertThat(userRepository.existsByEmail(email)).isFalse();
    }

    // ── 처음 온 사용자 ──

    @Test
    @DisplayName("처음 온 사용자: 티켓 → 닉네임 가입(201) → 비밀번호 없는 인증 완료 계정, 다음부터는 바로 로그인된다")
    void newUserSignsUpThenLogsIn() throws Exception {
        long kakaoId = newKakaoId();
        String email = newEmail();
        kakao.register("first", FakeKakaoOauthClient.verified(kakaoId, email.toUpperCase()));

        MvcResult callback = loginWith("first");
        assertRedirect(callback, "result", "signup");
        assertThat(callback.getResponse().getCookie("refreshToken")).isNull();
        assertThat(((MockCookie) callback.getResponse().getCookie("oauthState")).getMaxAge()).isZero();
        String ticket = signupTicketOf(callback);

        String nickname = nickname();
        MvcResult signedUp = signup(ticket, nickname);
        assertThat(signedUp.getResponse().getStatus()).isEqualTo(201);
        // 응답 바디는 로컬 회원가입과 같은 모양이다.
        String body = signedUp.getResponse().getContentAsString();
        assertThat(com.jayway.jsonpath.JsonPath.<String>read(body, "$.data.accessToken")).isNotBlank();
        assertThat(com.jayway.jsonpath.JsonPath.<String>read(body, "$.data.nickname")).isEqualTo(nickname);
        assertThat(com.jayway.jsonpath.JsonPath.<Boolean>read(body, "$.data.emailVerified")).isTrue();
        assertThat(signedUp.getResponse().getCookie("refreshToken").getValue()).isNotBlank();
        assertThat(((MockCookie) signedUp.getResponse().getCookie("oauthSignup")).getMaxAge()).isZero();
        mockMvc.perform(post(SIGNUP).contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"" + nickname() + "\"}")
                .cookie(new Cookie("oauthSignup", ticket)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUTH016"));

        // 이메일은 정규화(소문자)돼 저장되고, 비밀번호 없이 인증 완료 상태다.
        User user = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow();
        assertThat(user.getNickname()).isEqualTo(nickname);
        assertThat(user.hasPassword()).isFalse();
        assertThat(user.isEmailVerified()).isTrue();
        assertThat(oauthAccountRepository.findByProviderAndProviderUserId(OauthProvider.KAKAO, String.valueOf(kakaoId)))
                .get().extracting(a -> a.getUserId()).isEqualTo(user.getId());

        // 두 번째 방문: 이메일이 바뀌어 와도 회원번호로 찾아 바로 로그인된다.
        kakao.register("second", FakeKakaoOauthClient.verified(kakaoId, "changed-" + email));
        MvcResult again = loginWith("second");
        assertRedirect(again, "result", "login");
        assertThat(((MockCookie) again.getResponse().getCookie("oauthState")).getMaxAge()).isZero();
        MockCookie refresh = (MockCookie) again.getResponse().getCookie("refreshToken");
        assertThat(refresh.getPath()).isEqualTo("/api/auth");

        // 콜백에서 받은 refresh로 access를 받을 수 있다(프런트의 silent refresh).
        mockMvc.perform(post("/api/auth/reissue").cookie(new Cookie("refreshToken", refresh.getValue())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());
    }

    @Test
    @DisplayName("닉네임이 겹치면 AUTH002이고, 같은 티켓으로 다른 닉네임을 내면 가입된다")
    void duplicateNicknameKeepsTicket() throws Exception {
        User taken = localUser(newEmail(), true);
        String email = newEmail();
        kakao.register("c", FakeKakaoOauthClient.verified(newKakaoId(), email));
        String ticket = signupTicketOf(loginWith("c"));

        MvcResult duplicate = signup(ticket, taken.getNickname());
        assertThat(duplicate.getResponse().getStatus()).isEqualTo(409);
        assertThat(duplicate.getResponse().getContentAsString()).contains("AUTH002");
        assertThat(userRepository.existsByEmail(email)).isFalse();

        assertThat(signup(ticket, nickname()).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    @DisplayName("티켓을 받은 뒤 같은 이메일로 로컬 가입이 끼어들면 AUTH001이고 카카오 계정은 연결되지 않는다")
    void localSignupBetweenTicketAndSubmit() throws Exception {
        String email = newEmail();
        long kakaoId = newKakaoId();
        kakao.register("c", FakeKakaoOauthClient.verified(kakaoId, email));
        String ticket = signupTicketOf(loginWith("c"));
        localUser(email, false);

        assertThat(signup(ticket, nickname()).getResponse().getContentAsString()).contains("AUTH001");
        assertThat(oauthAccountRepository.findByProviderAndProviderUserId(OauthProvider.KAKAO, String.valueOf(kakaoId)))
                .isEmpty();
    }

    @Test
    @DisplayName("사전 검사를 통과하고 INSERT에서 UNIQUE가 터지면 C009로 되돌리고, 티켓은 소진하지 않는다")
    void uniqueViolationOnInsertRollsBackAndKeepsTicket() throws Exception {
        // 같은 카카오 계정이 이메일을 바꿔 가며 티켓을 두 장 받은 경우 — 두 번째는 연결 INSERT에서 막힌다.
        long kakaoId = newKakaoId();
        String first = newEmail();
        String second = newEmail();
        kakao.register("a", FakeKakaoOauthClient.verified(kakaoId, first));
        kakao.register("b", FakeKakaoOauthClient.verified(kakaoId, second));
        String ticketA = signupTicketOf(loginWith("a"));
        String ticketB = signupTicketOf(loginWith("b"));
        assertThat(signup(ticketA, nickname()).getResponse().getStatus()).isEqualTo(201);

        MvcResult conflict = signup(ticketB, nickname());
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(conflict.getResponse().getContentAsString()).contains("C009");
        assertThat(userRepository.existsByEmail(second)).isFalse(); // 계정 INSERT도 함께 되돌려졌다
        // 티켓이 살아 있어 AUTH016이 아니라 다시 같은 충돌(C009)이 난다.
        assertThat(signup(ticketB, nickname()).getResponse().getContentAsString()).contains("C009");
    }

    @Test
    @DisplayName("티켓 쿠키가 없거나 지어낸 값이면 AUTH016")
    void missingOrForgedTicket() throws Exception {
        assertThat(signup(null, nickname()).getResponse().getContentAsString()).contains("AUTH016");
        assertThat(signup("made-up", nickname()).getResponse().getContentAsString()).contains("AUTH016");
    }

    @Test
    @DisplayName("닉네임이 비었거나 1자·21자면 400(C001) — 티켓 검사보다 입력 검증이 먼저다")
    void invalidNicknameIsRejected() throws Exception {
        for (String nickname : List.of("", "가", "가".repeat(21))) {
            MvcResult result = signup("any-ticket", nickname);
            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(result.getResponse().getContentAsString()).contains("C001");
        }
    }

    @Test
    @DisplayName("탈퇴한 계정이 이메일을 쥐고 있으면 새로 가입시키지 않는다(AUTH015)")
    void deletedAccountHoldsEmail() throws Exception {
        String email = newEmail();
        User gone = localUser(email, true);
        jdbc.update("UPDATE users SET deleted_at = NOW() WHERE id = ?", gone.getId());
        kakao.register("c", FakeKakaoOauthClient.verified(newKakaoId(), email));

        assertRedirect(loginWith("c"), "error", "AUTH015");
    }

    @Test
    @DisplayName("악센트만 다른 주소의 계정(DB 콜레이션상 같은 값)에는 연결하지 않는다(AUTH015)")
    void accentVariantAccountIsNotLinked() throws Exception {
        String email = newEmail();                                    // kakao-N@safedeal.test
        String lookalike = email.replace("safedeal", "safedëal"); // kakao-N@safedëal.test
        emails.add(lookalike);
        User attacker = localUser(lookalike, true);
        kakao.register("c", FakeKakaoOauthClient.verified(newKakaoId(), email));

        MvcResult result = loginWith("c");
        assertRedirect(result, "error", "AUTH015");
        assertThat(result.getResponse().getCookie("refreshToken")).isNull();
        assertThat(oauthAccountRepository.existsByUserIdAndProvider(attacker.getId(), OauthProvider.KAKAO)).isFalse();
    }

    // ── 같은 이메일의 로컬 계정 ──

    @Test
    @DisplayName("이메일 인증을 마친 로컬 계정이면 자동으로 연결하고 로그인시킨다")
    void verifiedLocalAccountIsLinked() throws Exception {
        String email = newEmail();
        User local = localUser(email, true);
        long kakaoId = newKakaoId();
        kakao.register("c", FakeKakaoOauthClient.verified(kakaoId, email));

        MvcResult result = loginWith("c");
        assertRedirect(result, "result", "login");
        assertThat(result.getResponse().getCookie("refreshToken")).isNotNull();
        assertThat(oauthAccountRepository.findByProviderAndProviderUserId(OauthProvider.KAKAO, String.valueOf(kakaoId)))
                .get().extracting(a -> a.getUserId()).isEqualTo(local.getId());
    }

    @Test
    @DisplayName("이메일 미인증 로컬 계정에는 연결하지 않는다(AUTH015) — 남의 이메일로 먼저 가입한 계정을 가로채는 통로")
    void unverifiedLocalAccountIsNotLinked() throws Exception {
        String email = newEmail();
        User local = localUser(email, false);
        kakao.register("c", FakeKakaoOauthClient.verified(newKakaoId(), email));

        MvcResult result = loginWith("c");
        assertRedirect(result, "error", "AUTH015");
        assertThat(result.getResponse().getCookie("refreshToken")).isNull();
        assertThat(oauthAccountRepository.existsByUserIdAndProvider(local.getId(), OauthProvider.KAKAO)).isFalse();
    }

    @Test
    @DisplayName("이미 다른 카카오 계정과 연결된 로컬 계정이면 AUTH015")
    void alreadyLinkedToAnotherKakao() throws Exception {
        String email = newEmail();
        localUser(email, true);
        kakao.register("first", FakeKakaoOauthClient.verified(newKakaoId(), email));
        assertRedirect(loginWith("first"), "result", "login");

        kakao.register("other", FakeKakaoOauthClient.verified(newKakaoId(), email));
        assertRedirect(loginWith("other"), "error", "AUTH015");
    }

    @Test
    @DisplayName("정지된 계정은 연결돼 있어도, 이메일로 연결하려 해도 AUTH005이고 refresh를 주지 않는다")
    void suspendedAccountIsRejected() throws Exception {
        String email = newEmail();
        User local = localUser(email, true);
        long kakaoId = newKakaoId();
        kakao.register("link", FakeKakaoOauthClient.verified(kakaoId, email));
        assertRedirect(loginWith("link"), "result", "login");

        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", local.getId());
        MvcResult linked = loginWith("link");
        assertRedirect(linked, "error", "AUTH005");
        assertThat(linked.getResponse().getCookie("refreshToken")).isNull();

        String other = newEmail();
        User suspended = localUser(other, true);
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", suspended.getId());
        kakao.register("by-email", FakeKakaoOauthClient.verified(newKakaoId(), other));
        assertRedirect(loginWith("by-email"), "error", "AUTH005");
        assertThat(oauthAccountRepository.existsByUserIdAndProvider(suspended.getId(), OauthProvider.KAKAO)).isFalse();
    }

    @Test
    @DisplayName("연결된 계정이 탈퇴했으면 AUTH005이고 refresh를 주지 않는다")
    void deletedLinkedAccountIsRejected() throws Exception {
        String email = newEmail();
        User local = localUser(email, true);
        kakao.register("c", FakeKakaoOauthClient.verified(newKakaoId(), email));
        assertRedirect(loginWith("c"), "result", "login");

        jdbc.update("UPDATE users SET deleted_at = NOW() WHERE id = ?", local.getId());
        MvcResult result = loginWith("c");
        assertRedirect(result, "error", "AUTH005");
        assertThat(result.getResponse().getCookie("refreshToken")).isNull();
    }

    @Test
    @DisplayName("공개 경로는 정확히 셋뿐이다 — 같은 접두어의 다른 경로·메서드는 401")
    void onlyExactOauthPathsArePublic() throws Exception {
        mockMvc.perform(post(START)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(SIGNUP)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/oauth/other")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/oauth/kakao/unlink")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("카카오로 가입한 계정(비밀번호 없음)은 이메일·비밀번호 로그인이 AUTH003이다")
    void oauthOnlyAccountCannotLocalLogin() throws Exception {
        String email = newEmail();
        kakao.register("c", FakeKakaoOauthClient.verified(newKakaoId(), email));
        assertThat(signup(signupTicketOf(loginWith("c")), nickname()).getResponse().getStatus()).isEqualTo(201);

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH003"));
    }

    // ── 동시성 ──

    @Test
    @DisplayName("같은 가입 티켓으로 동시에 3번 내도 계정은 하나만 생긴다")
    void concurrentSignupWithSameTicketCreatesOneAccount() throws Exception {
        String email = newEmail();
        long kakaoId = newKakaoId();
        kakao.register("c", FakeKakaoOauthClient.verified(kakaoId, email));
        String ticket = signupTicketOf(loginWith("c"));

        List<Throwable> failures = runConcurrently(3,
                i -> () -> oauthCommandService.completeSignup(ticket, "동시가입" + i + (SEQ.get() % 100_000)));

        assertThat(failures).hasSize(2).allMatch(t -> t instanceof BusinessException e
                && (e.getErrorCode() == AuthErrorCode.INVALID_OAUTH_SIGNUP_TICKET
                || e.getErrorCode() == AuthErrorCode.DUPLICATE_EMAIL
                || e.getErrorCode() == CommonErrorCode.CONCURRENT_MODIFICATION));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oauth_accounts WHERE provider_user_id = ?",
                Integer.class, String.valueOf(kakaoId))).isOne();
    }

    @Test
    @DisplayName("같은 로컬 계정에 동시에 두 콜백이 연결을 시도해도 연결은 하나만 생긴다")
    void concurrentAutoLinkCreatesOneLink() throws Exception {
        String email = newEmail();
        User local = localUser(email, true);
        long kakaoId = newKakaoId();
        kakao.register("a", FakeKakaoOauthClient.verified(kakaoId, email));
        kakao.register("b", FakeKakaoOauthClient.verified(kakaoId, email));
        String stateA = start();
        String stateB = start();

        List<Throwable> failures = runConcurrently(2, i -> () -> i == 0
                ? oauthCommandService.callback("a", stateA, stateA, null)
                : oauthCommandService.callback("b", stateB, stateB, null));

        // 늦은 쪽은 연결이 이미 생긴 뒤면 그 연결로 로그인하고(실패 0), 동시에 INSERT하면 C009로 진다.
        assertThat(failures).hasSizeLessThanOrEqualTo(1).allMatch(t -> t instanceof BusinessException e
                && e.getErrorCode() == CommonErrorCode.CONCURRENT_MODIFICATION);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oauth_accounts WHERE user_id = ?",
                Integer.class, local.getId())).isOne();
    }

    private interface Action {
        Object run() throws Exception;
    }

    private List<Throwable> runConcurrently(int threads, java.util.function.IntFunction<Action> actionOf) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Throwable>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Action action = actionOf.apply(i);
                tasks.add(() -> {
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
}
