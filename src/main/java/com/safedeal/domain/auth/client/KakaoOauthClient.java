package com.safedeal.domain.auth.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.safedeal.domain.auth.exception.AuthErrorCode;
import com.safedeal.global.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 카카오 로그인 REST 호출 (AUTH-5). 엔드포인트·파라미터는 docs/AUTH-5-oauth-준비.md 2장.
 *
 * 카카오 토큰은 사용자 식별에 한 번만 쓰고 버린다 — 이후 세션은 우리 JWT가 맡으므로 저장하지 않는다.
 *
 * 실패는 삼키지 않고 AUTH012로 바꿔 올린다(ResendMailSender와 반대) — 메일은 못 보내도 가입이
 * 진행돼야 하지만, 카카오 확인이 실패하면 로그인 자체가 실패여야 한다. 원래 예외를 그대로 올리지
 * 않는 이유: 전역 핸들러가 미처리 예외의 스택 전체를 로그에 남기는데, 거기에 요청 주소(code 포함)나
 * 응답 본문이 실릴 수 있다. 그래서 예외 클래스 이름만 남긴다.
 *
 * 타임아웃을 거는 이유: 카카오가 응답하지 않으면 요청 스레드가 무한정 묶인다.
 *
 * 테스트는 이 클래스를 상속한 가짜로 바꿔 끼운다(testsupport/FakeKakaoOauthClient) — 그래서 final이 아니다.
 */
@Slf4j
@Component
public class KakaoOauthClient {

    private static final String AUTHORIZE_URL = "https://kauth.kakao.com/oauth/authorize";
    private static final String TOKEN_URL = "https://kauth.kakao.com/oauth/token";
    private static final String USER_ME_URL = "https://kapi.kakao.com/v2/user/me";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final KakaoOauthProperties properties;
    private final RestClient restClient;

    @Autowired
    public KakaoOauthClient(KakaoOauthProperties properties) {
        this(properties, RestClient.builder().requestFactory(timeoutRequestFactory()).build());
    }

    /** 테스트가 가짜 HTTP 서버(MockRestServiceServer)를 붙인 RestClient를 넣을 때 쓴다. */
    KakaoOauthClient(KakaoOauthProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    private static JdkClientHttpRequestFactory timeoutRequestFactory() {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return requestFactory;
    }

    /** 카카오 동의 화면 주소. state는 URL-safe 값(SecureToken)이라 그대로 실어도 안전하다. */
    public URI authorizeUri(String state) {
        return UriComponentsBuilder.fromUriString(AUTHORIZE_URL)
                .queryParam("client_id", properties.getClientId())
                .queryParam("redirect_uri", properties.getRedirectUri())
                .queryParam("response_type", "code")
                .queryParam("state", state)
                .encode()
                .build()
                .toUri();
    }

    /** 인가 코드를 토큰으로 바꾸고, 그 토큰으로 사용자 정보를 받아온다. */
    public KakaoProfile fetchProfile(String code) {
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "authorization_code");
            form.add("client_id", properties.getClientId());
            form.add("client_secret", properties.getClientSecret());
            form.add("redirect_uri", properties.getRedirectUri());
            form.add("code", code);

            TokenResponse token = restClient.post()
                    .uri(TOKEN_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);
            if (token == null || token.accessToken() == null) {
                throw new BusinessException(AuthErrorCode.OAUTH_PROVIDER_FAILED);
            }

            KakaoProfile profile = restClient.get()
                    .uri(USER_ME_URL)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.accessToken())
                    .retrieve()
                    .body(KakaoProfile.class);
            if (profile == null || profile.id() == null) {
                throw new BusinessException(AuthErrorCode.OAUTH_PROVIDER_FAILED);
            }
            return profile;
        } catch (RestClientException e) {
            log.warn("카카오 호출 실패 - 로그인을 중단한다. reason={}", e.getClass().getSimpleName());
            throw new BusinessException(AuthErrorCode.OAUTH_PROVIDER_FAILED);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(@JsonProperty("access_token") String accessToken) {
    }
}
