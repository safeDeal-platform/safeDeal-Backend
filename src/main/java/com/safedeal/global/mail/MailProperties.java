package com.safedeal.global.mail;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "app.mail")
public class MailProperties {

    /** log | resend — 어떤 구현체를 띄울지 결정한다. 기본은 log(콘솔 출력). */
    private String provider = "log";

    /** 발신자 주소. Resend는 도메인 인증 전이면 onboarding@resend.dev만 허용한다. */
    private String from = "onboarding@resend.dev";

    /** Resend API 키. provider=resend일 때만 필요하다. */
    private String apiKey;

    /** 메일 본문의 링크가 가리킬 프런트엔드 기준 URL. */
    private String baseUrl = "http://localhost:3000";
}
