package com.safedeal.domain.auth;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockCookie;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 인증 한 사이클 회귀 테스트 — 회원가입·로그인·재발급(RTR)·로그아웃 (AUTH-1 ~ AUTH-4).
 *
 * 여기서 고정하는 것은 대외 계약과 보안 가정이다: 응답 형식, refresh가 바디가 아니라 쿠키로
 * 나가는 것, 계정 열거를 막기 위해 실패 응답을 같게 유지하는 것, RTR 재사용 감지, 로그아웃 후
 * 블랙리스트 차단. 깨지면 프런트와 보안 가정이 함께 깨지므로 고치기 전에 팀에 공유할 것.
 *
 * 프로파일이 (local, test)인 이유는 ApiContractTest와 같다 — local은 개발 인증 필터를 켜고,
 * test는 logback의 Loki 전송을 끈다. 보호 경로 확인에는 테스트 전용 프로브(/__test/me)를 쓴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
@Testcontainers
class AuthFlowTest {

    private static final String PROTECTED_PROBE = "/__test/me";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    private static int sequence = 0;

    /** 테스트끼리 이메일·닉네임 UNIQUE가 충돌하지 않도록 매번 다른 값을 쓴다. */
    private static String unique(String prefix) {
        return prefix + (++sequence);
    }

    private String signupBody(String email, String nickname) {
        return """
                {"email":"%s","password":"password123","nickname":"%s"}
                """.formatted(email, nickname);
    }

    private MvcResult signup(String email, String nickname) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody(email, nickname)))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private String accessTokenOf(MvcResult result) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.path("data").path("accessToken").asText();
    }

    @Test
    @DisplayName("가입하면 곧바로 로그인 상태가 되고, access는 바디로 refresh는 httpOnly 쿠키로 나간다")
    void signupIssuesTokens() throws Exception {
        MvcResult result = signup(unique("signup") + "@test.com", unique("가입자"));

        assertThat(accessTokenOf(result)).isNotBlank();

        MockCookie refresh = (MockCookie) result.getResponse().getCookie("refreshToken");
        assertThat(refresh).isNotNull();
        assertThat(refresh.isHttpOnly()).isTrue();
        assertThat(refresh.getSecure()).isTrue();
        assertThat(refresh.getPath()).isEqualTo("/api/v1/auth");
        // refresh가 바디에도 실리면 httpOnly를 둔 이유가 통째로 사라진다.
        assertThat(result.getResponse().getContentAsString()).doesNotContain(refresh.getValue());
    }

    @Test
    @DisplayName("발급받은 access로 보호된 경로에 접근할 수 있고, principal이 채워진다")
    void accessTokenAuthenticatesProtectedApi() throws Exception {
        MvcResult signedUp = signup(unique("protected") + "@test.com", unique("보호"));
        String token = accessTokenOf(signedUp);

        mockMvc.perform(get(PROTECTED_PROBE).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("USER"));

        mockMvc.perform(get(PROTECTED_PROBE))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("가입한 계정으로 로그인하면 새 토큰이 나온다")
    void loginIssuesTokens() throws Exception {
        String email = unique("login") + "@test.com";
        signup(email, unique("로그인"));

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"password123"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(accessTokenOf(result)).isNotBlank();
        assertThat(result.getResponse().getCookie("refreshToken")).isNotNull();
    }

    @Test
    @DisplayName("중복 이메일은 AUTH001, 중복 닉네임은 AUTH002")
    void duplicateSignupIsRejected() throws Exception {
        String email = unique("dup") + "@test.com";
        String nickname = unique("중복");
        signup(email, nickname);

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody(email, unique("다른닉"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("AUTH001"));

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody(unique("other") + "@test.com", nickname)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("AUTH002"));
    }

    @Test
    @DisplayName("없는 계정과 틀린 비밀번호는 완전히 같은 401을 준다 (계정 열거 방지)")
    void loginFailuresAreIndistinguishable() throws Exception {
        String email = unique("enum") + "@test.com";
        signup(email, unique("열거"));

        String wrongPassword = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"totally-wrong-password"}
                                """.formatted(email)))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String unknownAccount = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"no-such-account@test.com","password":"totally-wrong-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        // requestId만 다르므로 에러 코드·메시지가 같은지로 비교한다.
        JsonNode a = objectMapper.readTree(wrongPassword).path("error");
        JsonNode b = objectMapper.readTree(unknownAccount).path("error");
        assertThat(a.path("code").asText()).isEqualTo(b.path("code").asText()).isEqualTo("AUTH003");
        assertThat(a.path("message").asText()).isEqualTo(b.path("message").asText());
    }

    @Test
    @DisplayName("재발급하면 새 토큰이 나오고, 직전 refresh를 다시 쓰면 재사용으로 보고 전 세션을 끊는다")
    void reissueRotatesAndDetectsReuse() throws Exception {
        MvcResult signedUp = signup(unique("rtr") + "@test.com", unique("회전"));
        MockCookie first = (MockCookie) signedUp.getResponse().getCookie("refreshToken");
        assertThat(first).isNotNull();

        MvcResult reissued = mockMvc.perform(post("/api/v1/auth/reissue").cookie(first))
                .andExpect(status().isOk())
                .andReturn();
        MockCookie second = (MockCookie) reissued.getResponse().getCookie("refreshToken");
        assertThat(second).isNotNull();
        assertThat(second.getValue()).isNotEqualTo(first.getValue());

        // 직전 토큰은 RTR로 폐기됐다 → 화이트리스트에 없으므로 재사용 공격으로 판정된다.
        mockMvc.perform(post("/api/v1/auth/reissue").cookie(first))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH004"));

        // 재사용이 감지되면 그 유저의 모든 세션이 끊긴다 → 방금 받은 새 토큰도 못 쓴다.
        mockMvc.perform(post("/api/v1/auth/reissue").cookie(second))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("refresh 쿠키 없이 재발급하면 AUTH007")
    void reissueWithoutCookie() throws Exception {
        mockMvc.perform(post("/api/v1/auth/reissue"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH007"));
    }

    @Test
    @DisplayName("로그아웃하면 access가 블랙리스트에 올라가고 쿠키가 삭제된다")
    void logoutBlacklistsAccessAndClearsCookie() throws Exception {
        MvcResult signedUp = signup(unique("logout") + "@test.com", unique("로그아웃"));
        String token = accessTokenOf(signedUp);
        MockCookie refresh = (MockCookie) signedUp.getResponse().getCookie("refreshToken");

        MvcResult loggedOut = mockMvc.perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .cookie(refresh))
                .andExpect(status().isOk())
                .andReturn();

        MockCookie cleared = (MockCookie) loggedOut.getResponse().getCookie("refreshToken");
        assertThat(cleared).isNotNull();
        assertThat(cleared.getMaxAge()).isZero();

        // 서명은 여전히 유효하지만 블랙리스트에 걸려 더 이상 인증되지 않는다.
        mockMvc.perform(get(PROTECTED_PROBE).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());

        // 폐기된 refresh로는 재발급도 안 된다.
        mockMvc.perform(post("/api/v1/auth/reissue").cookie(refresh))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("로그아웃은 토큰이 하나도 없어도 200 (만료 후에도 쿠키는 지워져야 한다)")
    void logoutWithoutTokensStillSucceeds() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isOk())
                .andReturn();
        MockCookie cleared = (MockCookie) result.getResponse().getCookie("refreshToken");
        assertThat(cleared).isNotNull();
        assertThat(cleared.getMaxAge()).isZero();
    }

    @Test
    @DisplayName("위조 토큰으로는 인증되지 않는다")
    void forgedTokenIsRejected() throws Exception {
        mockMvc.perform(get(PROTECTED_PROBE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer forged.token.value"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("refresh 토큰을 Authorization 헤더에 넣어도 인증되지 않는다 (typ 구분)")
    void refreshTokenCannotBeUsedAsAccess() throws Exception {
        MvcResult signedUp = signup(unique("typ") + "@test.com", unique("타입"));
        MockCookie refresh = (MockCookie) signedUp.getResponse().getCookie("refreshToken");
        assertThat(refresh).isNotNull();

        mockMvc.perform(get(PROTECTED_PROBE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + refresh.getValue()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("공개 경로는 토큰 없이도 열려 있다 (인증 필터가 공개 API를 막지 않는다)")
    void publicEndpointsRemainOpen() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
