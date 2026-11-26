package com.safedeal.domain.auth.client;

import com.safedeal.domain.auth.exception.AuthErrorCode;
import com.safedeal.global.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 실제 카카오 클라이언트의 요청 모양과 응답 해석. 카카오 서버 대신 가짜 HTTP 서버(MockRestServiceServer)를 붙인다.
 * 통합 테스트는 클라이언트 전체를 가짜로 바꿔 끼우므로, JSON 필드 이름(snake_case)이 실제로 읽히는지는 여기서만 확인된다.
 */
class KakaoOauthClientTest {

    private static final String TOKEN_URL = "https://kauth.kakao.com/oauth/token";
    private static final String USER_ME_URL = "https://kapi.kakao.com/v2/user/me";

    // docs/AUTH-5-oauth-준비.md 「사용자 정보 응답」 예시 그대로(모르는 필드 포함).
    private static final String USER_ME = """
            { "id": 123456789,
              "connected_at": "2022-04-11T01:45:28Z",
              "kakao_account": {
                "profile_needs_agreement": false,
                "profile": { "nickname": "홍길동", "is_default_nickname": false },
                "email_needs_agreement": false,
                "is_email_valid": true,
                "is_email_verified": true,
                "email": "user@example.com"
              } }
            """;

    private MockRestServiceServer server;
    private KakaoOauthClient client;

    @BeforeEach
    void setUp() {
        KakaoOauthProperties properties = new KakaoOauthProperties();
        properties.setClientId("rest-api-key");
        properties.setClientSecret("secret");
        properties.setRedirectUri("http://localhost:8080/api/auth/oauth/kakao/callback");
        properties.setFrontendCallbackUrl("http://localhost:3000/oauth/callback");

        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new KakaoOauthClient(properties, builder.build());
    }

    private void expectToken() {
        server.expect(requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formDataContains(java.util.Map.of(
                        "grant_type", "authorization_code",
                        "client_id", "rest-api-key",
                        "client_secret", "secret",
                        "redirect_uri", "http://localhost:8080/api/auth/oauth/kakao/callback",
                        "code", "auth-code")))
                .andRespond(withSuccess("{\"token_type\":\"bearer\",\"access_token\":\"kakao-at\",\"expires_in\":43199}",
                        MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("코드를 토큰으로 바꾸고 그 토큰으로 사용자 정보를 읽는다 — snake_case 필드가 실제로 바인딩된다")
    void fetchesProfileAndBindsSnakeCaseFields() {
        expectToken();
        server.expect(requestTo(USER_ME_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer kakao-at"))
                .andRespond(withSuccess(USER_ME, MediaType.APPLICATION_JSON));

        KakaoProfile profile = client.fetchProfile("auth-code");

        assertThat(profile.id()).isEqualTo(123456789L);
        assertThat(profile.verifiedEmail()).contains("user@example.com");
        server.verify();
    }

    @Test
    @DisplayName("미검증 이메일이 오면 바인딩된 값으로 거른다")
    void unverifiedEmailIsFiltered() {
        expectToken();
        server.expect(requestTo(USER_ME_URL))
                .andRespond(withSuccess(USER_ME.replace("\"is_email_verified\": true", "\"is_email_verified\": false"),
                        MediaType.APPLICATION_JSON));

        assertThat(client.fetchProfile("auth-code").verifiedEmail()).isEmpty();
    }

    @Test
    @DisplayName("토큰 요청이 실패하면(4xx) AUTH012")
    void tokenFailureIsProviderFailure() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertProviderFailed();
    }

    @Test
    @DisplayName("토큰 응답에 access_token이 없으면 AUTH012")
    void missingAccessTokenIsProviderFailure() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertProviderFailed();
    }

    @Test
    @DisplayName("사용자 정보 요청이 실패하거나 id가 없으면 AUTH012")
    void userMeFailureIsProviderFailure() {
        expectToken();
        server.expect(requestTo(USER_ME_URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertProviderFailed();

        server.reset();
        expectToken();
        server.expect(requestTo(USER_ME_URL)).andRespond(withSuccess("{\"kakao_account\":{}}", MediaType.APPLICATION_JSON));
        assertProviderFailed();
    }

    private void assertProviderFailed() {
        assertThatThrownBy(() -> client.fetchProfile("auth-code"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.OAUTH_PROVIDER_FAILED));
    }
}
