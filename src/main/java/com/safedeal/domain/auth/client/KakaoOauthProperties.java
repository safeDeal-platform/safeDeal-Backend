package com.safedeal.domain.auth.client;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 카카오 로그인 설정 (AUTH-5). 값은 .env(KAKAO_*)나 배포 환경변수로 들어온다.
 *
 * {@code @NotBlank}로 기동을 막지 않는 이유: 카카오 키가 없는 팀원도 로컬에서 앱을 띄워야 하고,
 * .env에 빈 줄(KAKAO_CLIENT_ID=)만 있으면 기본값 대신 빈 문자열이 들어온다. 키가 비면 카카오
 * 호출이 실패해 AUTH012로 끝날 뿐이다. 운영 누락은 RequiredPropertyGuard가 기동에서 막는다.
 */
@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "app.oauth.kakao")
public class KakaoOauthProperties {

    /** REST API 키. */
    private String clientId;

    /** 클라이언트 시크릿. 콘솔에서 켜 둔 상태라 토큰 요청에 필수다. */
    private String clientSecret;

    /** 카카오 콘솔에 등록한 콜백 주소와 글자 하나까지 같아야 한다(다르면 KOE006). */
    private String redirectUri;

    /** 콜백 처리 후 브라우저를 보낼 프런트 주소. 요청값이 아니라 설정으로만 정한다(오픈 리다이렉트 방지). */
    private String frontendCallbackUrl;
}
