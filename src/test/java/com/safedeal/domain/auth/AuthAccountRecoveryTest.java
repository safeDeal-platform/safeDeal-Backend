package com.safedeal.domain.auth;

import com.safedeal.domain.auth.entity.EmailVerificationToken;
import com.safedeal.domain.auth.entity.PasswordResetToken;
import com.safedeal.domain.auth.repository.EmailVerificationTokenRepository;
import com.safedeal.domain.auth.repository.PasswordResetTokenRepository;
import com.safedeal.domain.user.repository.UserRepository;
import com.safedeal.global.security.JwtTokenProvider;
import com.safedeal.global.util.SecureToken;
import com.safedeal.testsupport.RecordingMailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
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

import java.time.Instant;
import java.util.Set;

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

    @Autowired
    UserRepository userRepository;

    @Autowired
    EmailVerificationTokenRepository emailVerificationTokenRepository;

    @Autowired
    PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    StringRedisTemplate redisTemplate;

    private static int sequence = 0;

    private static String unique(String prefix) {
        return prefix + (++sequence);
    }

    /**
     * 우편함과 발송 제한 카운터를 테스트마다 비운다.
     *
     * 카운터를 비우는 이유: 제한 키는 이메일 해시와 <b>IP</b>로 만들어지는데 MockMvc의 요청은
     * 전부 같은 IP에서 온다. 비우지 않으면 재설정을 호출하는 테스트가 쌓이다가 어느 순간
     * 뒤쪽 테스트만 429로 깨진다 — 실행 순서에 따라 붙었다 떨어졌다 하는 테스트가 된다.
     */
    @BeforeEach
    void resetMailboxAndRateLimits() {
        mailSender.clear();
        Set<String> keys = redisTemplate.keys("auth:mail-rate:v1:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
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

        // 응답이 조금이라도 다르면 이 API가 로그인 없이 쓸 수 있는 가입 여부 조회기가 된다.
        // 특정 필드가 아니라 본문 전체를 비교한다 - 나중에 누가 data에 상태를 하나 얹는 것까지 잡아야 한다.
        assertThat(forRegistered).isEqualTo(forUnregistered);
        assertThat(mailSender.sentTo(registered)).hasSize(1);
        assertThat(mailSender.sentTo(unregistered)).isEmpty();
    }

    // ---------- 만료 ----------

    /**
     * 만료는 시계를 앞당길 수 없어 이미 지난 expiresAt을 가진 행을 직접 넣어 확인한다.
     *
     * TTL 자체(24시간·30분)는 서비스 상수라 여기서 값을 검증하지는 못한다. 다만 만료 판정이
     * 실제로 걸리는지는 확인해야 한다 — isUsable에서 expiresAt 비교가 빠져도 나머지 테스트는
     * 전부 통과하기 때문이다.
     */
    private String issueExpiredVerificationToken(String email) {
        Long userId = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId();
        String raw = SecureToken.generate();
        emailVerificationTokenRepository.save(EmailVerificationToken.issue(
                userId, JwtTokenProvider.hash(raw), Instant.now().minusSeconds(1)));
        return raw;
    }

    private String issueExpiredResetToken(String email) {
        Long userId = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId();
        String raw = SecureToken.generate();
        passwordResetTokenRepository.save(PasswordResetToken.issue(
                userId, JwtTokenProvider.hash(raw), Instant.now().minusSeconds(1)));
        return raw;
    }

    @Test
    @DisplayName("만료된 인증 토큰은 거부된다")
    void expiredVerificationTokenIsRejected() throws Exception {
        String email = unique("expired-verify") + "@test.com";
        signup(email, unique("만료인증"));

        verify(issueExpiredVerificationToken(email))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUTH008"));
    }

    @Test
    @DisplayName("만료된 재설정 토큰은 거부되고 비밀번호도 그대로다")
    void expiredResetTokenIsRejected() throws Exception {
        String email = unique("expired-reset") + "@test.com";
        signup(email, unique("만료재설정"));

        reset(issueExpiredResetToken(email), "new-password-1234")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUTH009"));

        // 400을 주면서 비밀번호는 바꿔버리면 최악이므로 로그인으로 확인한다.
        login(email, "password123").andExpect(status().isOk());
    }

    // ---------- 발송 제한 ----------

    @Test
    @DisplayName("재설정 요청은 주소당 시간당 3통까지고, 넘으면 AUTH010")
    void resetRequestIsRateLimitedPerAddress() throws Exception {
        String email = unique("flood") + "@test.com";
        signup(email, unique("폭주"));
        mailSender.clear();

        for (int attempt = 1; attempt <= 3; attempt++) {
            resetRequest(email).andExpect(status().isOk());
        }
        resetRequest(email)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("AUTH010"));

        // 제한에 걸린 요청은 메일을 만들지 않아야 한다. 429만 주고 메일은 나가면
        // 메일함 폭격도 공급자 쿼터 소모도 그대로다.
        assertThat(mailSender.sentTo(email)).hasSize(3);
    }

    @Test
    @DisplayName("가입되지 않은 주소도 똑같이 제한된다 (429 여부로 가입을 알 수 없다)")
    void rateLimitDoesNotRevealWhetherAccountExists() throws Exception {
        String registered = unique("limited-known") + "@test.com";
        String unregistered = unique("limited-unknown") + "@test.com";
        signup(registered, unique("있는계정"));
        mailSender.clear();

        for (int attempt = 1; attempt <= 3; attempt++) {
            resetRequest(registered).andExpect(status().isOk());
            resetRequest(unregistered).andExpect(status().isOk());
        }

        // 계정 조회보다 제한 검사가 앞에 있어야 여기서 두 응답이 같다. 순서가 뒤집히면
        // 없는 주소는 계속 200이고 있는 주소만 429가 되어, 이 API가 다시 가입 여부 조회기가 된다.
        String forRegistered = resetRequest(registered)
                .andExpect(status().isTooManyRequests()).andReturn().getResponse().getContentAsString();
        String forUnregistered = resetRequest(unregistered)
                .andExpect(status().isTooManyRequests()).andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(forRegistered).path("error").path("code").asText())
                .isEqualTo(objectMapper.readTree(forUnregistered).path("error").path("code").asText());
    }

    @Test
    @DisplayName("인증 메일 재발송도 계정당 시간당 3통까지다")
    void verificationResendIsRateLimited() throws Exception {
        String email = unique("resend-limit") + "@test.com";
        MvcResult signedUp = signup(email, unique("재발송제한"));
        String access = accessTokenOf(signedUp);
        mailSender.clear();

        for (int attempt = 1; attempt <= 3; attempt++) {
            mockMvc.perform(post("/api/v1/auth/email/verification")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(post("/api/v1/auth/email/verification")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("AUTH010"));

        assertThat(mailSender.sentTo(email)).hasSize(3);
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
