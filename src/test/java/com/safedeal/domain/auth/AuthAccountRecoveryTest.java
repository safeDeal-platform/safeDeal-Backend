package com.safedeal.domain.auth;

import com.safedeal.testsupport.RecordingMailSender;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 이메일 인증·비밀번호 찾기 (AUTH-6 · AUTH-7).
 *
 * 토큰이 해시로만 저장되므로 DB에서 원문을 꺼낼 수 없다. 그래서 실제 사용자와 같은 경로로
 * 검증한다 — 발송된 메일 본문의 링크에서 토큰을 읽어 API에 넣는다({@link RecordingMailSender}).
 *
 * 여기서 고정하는 것은 두 기능의 보안 가정이다: 토큰이 1회용이라는 것, 만료·사용됨·없음을
 * 구분해 주지 않는 것, 재설정 요청이 가입 여부를 드러내지 않는 것, 재설정이 기존 세션을
 * 전부 끊는 것.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
@Testcontainers
class AuthAccountRecoveryTest {

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

    @Autowired
    RecordingMailSender mailSender;

    private static int sequence = 0;

    private static String unique(String prefix) {
        return prefix + (++sequence);
    }

    @BeforeEach
    void resetMailbox() {
        mailSender.clear();
    }

    private MvcResult signup(String email, String nickname) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"password123","nickname":"%s"}
                                """.formatted(email, nickname)))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private String accessTokenOf(MvcResult result) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.path("data").path("accessToken").asText();
    }

    private ResultActions verify(String token) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/email/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"token":"%s"}
                        """.formatted(token)));
    }

    private ResultActions resetRequest(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/password/reset-request")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s"}
                        """.formatted(email)));
    }

    private ResultActions reset(String token, String newPassword) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/password/reset")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"token":"%s","newPassword":"%s"}
                        """.formatted(token, newPassword)));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s"}
                        """.formatted(email, password)));
    }

    // ---------- AUTH-6 이메일 인증 ----------

    @Test
    @DisplayName("가입하면 인증 메일이 나가고, 링크의 토큰으로 인증을 완료할 수 있다")
    void signupSendsVerificationMailAndTokenVerifies() throws Exception {
        String email = unique("verify") + "@test.com";
        signup(email, unique("인증"));

        assertThat(mailSender.sentTo(email)).hasSize(1);
        verify(mailSender.tokenFromLastMailTo(email)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("인증 토큰은 1회용이라 같은 링크를 두 번 쓰면 거부된다")
    void verificationTokenIsSingleUse() throws Exception {
        String email = unique("once") + "@test.com";
        signup(email, unique("일회용"));
        String token = mailSender.tokenFromLastMailTo(email);

        verify(token).andExpect(status().isOk());
        verify(token)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUTH008"));
    }

    @Test
    @DisplayName("이미 쓴 토큰과 없는 토큰은 완전히 같은 응답을 준다 (존재 여부를 흘리지 않는다)")
    void usedAndUnknownTokensAreIndistinguishable() throws Exception {
        String email = unique("opaque") + "@test.com";
        signup(email, unique("불투명"));
        String token = mailSender.tokenFromLastMailTo(email);
        verify(token).andExpect(status().isOk());

        String used = verify(token).andReturn().getResponse().getContentAsString();
        String unknown = verify("this-token-never-existed").andReturn().getResponse().getContentAsString();

        JsonNode a = objectMapper.readTree(used).path("error");
        JsonNode b = objectMapper.readTree(unknown).path("error");
        assertThat(a.path("code").asText()).isEqualTo(b.path("code").asText()).isEqualTo("AUTH008");
        assertThat(a.path("message").asText()).isEqualTo(b.path("message").asText());
    }

    @Test
    @DisplayName("재발송은 로그인해야 쓸 수 있고, 이미 인증된 계정에는 새 링크를 만들지 않는다")
    void resendRequiresLoginAndSkipsVerifiedAccounts() throws Exception {
        // 비로그인 호출은 막는다 - 열어두면 남의 주소로 메일을 대신 쏘는 발송기가 된다.
        mockMvc.perform(post("/api/v1/auth/email/verification"))
                .andExpect(status().isUnauthorized());

        String email = unique("resend") + "@test.com";
        MvcResult signedUp = signup(email, unique("재발송"));
        String access = accessTokenOf(signedUp);

        mockMvc.perform(post("/api/v1/auth/email/verification")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
                .andExpect(status().isOk());
        assertThat(mailSender.sentTo(email)).hasSize(2);

        // 인증을 끝낸 뒤에는 링크를 더 만들지 않는다. 계속 발급하면 살아 있는 링크만 늘어난다.
        verify(mailSender.tokenFromLastMailTo(email)).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/auth/email/verification")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
                .andExpect(status().isOk());
        assertThat(mailSender.sentTo(email)).hasSize(2);
    }

    // ---------- AUTH-7 비밀번호 찾기 ----------

    @Test
    @DisplayName("재설정 링크로 새 비밀번호를 설정하면 새 것으로만 로그인된다")
    void passwordResetChangesPassword() throws Exception {
        String email = unique("reset") + "@test.com";
        signup(email, unique("재설정"));
        mailSender.clear();

        resetRequest(email).andExpect(status().isOk());
        reset(mailSender.tokenFromLastMailTo(email), "new-password-1234").andExpect(status().isOk());

        login(email, "new-password-1234").andExpect(status().isOk());
        login(email, "password123")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH003"));
    }

    @Test
    @DisplayName("비밀번호를 재설정하면 기존 refresh가 전부 끊긴다")
    void passwordResetRevokesExistingSessions() throws Exception {
        String email = unique("revoke") + "@test.com";
        MvcResult signedUp = signup(email, unique("세션"));
        MockCookie refresh = (MockCookie) signedUp.getResponse().getCookie("refreshToken");
        assertThat(refresh).isNotNull();
        mailSender.clear();

        resetRequest(email).andExpect(status().isOk());
        reset(mailSender.tokenFromLastMailTo(email), "new-password-1234").andExpect(status().isOk());

        // 계정을 빼앗긴 상황이 대부분이라, 비밀번호만 바꾸고 공격자 세션이 살아 있으면 의미가 없다.
        mockMvc.perform(post("/api/v1/auth/reissue").cookie(refresh))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("재설정 토큰도 1회용이고, 두 번째 시도는 실제로 무시된다")
    void resetTokenIsSingleUse() throws Exception {
        String email = unique("reset-once") + "@test.com";
        signup(email, unique("한번만"));
        mailSender.clear();

        resetRequest(email).andExpect(status().isOk());
        String token = mailSender.tokenFromLastMailTo(email);

        reset(token, "new-password-1234").andExpect(status().isOk());
        reset(token, "another-password-5678")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUTH009"));

        // 응답만 400이고 비밀번호가 실제로 바뀌면 아무 소용이 없으므로 로그인까지 확인한다.
        login(email, "new-password-1234").andExpect(status().isOk());
    }

    @Test
    @DisplayName("가입되지 않은 이메일로 재설정을 요청해도 응답이 같고 메일은 나가지 않는다")
    void resetRequestDoesNotRevealWhetherAccountExists() throws Exception {
        String registered = unique("known") + "@test.com";
        String unregistered = unique("unknown") + "@test.com";
        signup(registered, unique("가입됨"));
        mailSender.clear();

        String forRegistered = resetRequest(registered)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String forUnregistered = resetRequest(unregistered)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        // 응답이 다르면 이 API가 로그인 없이 쓸 수 있는 가입 여부 조회기가 된다.
        assertThat(objectMapper.readTree(forRegistered).path("data"))
                .isEqualTo(objectMapper.readTree(forUnregistered).path("data"));
        assertThat(mailSender.sentTo(registered)).hasSize(1);
        assertThat(mailSender.sentTo(unregistered)).isEmpty();
    }

    @Test
    @DisplayName("없는 재설정 토큰은 AUTH009로 거부된다")
    void unknownResetTokenIsRejected() throws Exception {
        reset("this-token-never-existed", "new-password-1234")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUTH009"));
    }

    @Test
    @DisplayName("새 비밀번호가 8자 미만이면 걸러지고, 그때 토큰은 아직 살아 있다")
    void resetRejectsWeakPasswordWithoutBurningToken() throws Exception {
        String email = unique("weak") + "@test.com";
        signup(email, unique("약한"));
        mailSender.clear();

        resetRequest(email).andExpect(status().isOk());
        String token = mailSender.tokenFromLastMailTo(email);

        // 가입과 같은 제약(8자 이상). 여기만 느슨하면 재설정이 정책 우회 통로가 된다.
        reset(token, "short").andExpect(status().isBadRequest());

        // 검증에서 튕긴 요청이 토큰을 태우면 사용자는 아무 잘못 없이 링크를 다시 받아야 한다.
        reset(token, "new-password-1234").andExpect(status().isOk());
    }
}
