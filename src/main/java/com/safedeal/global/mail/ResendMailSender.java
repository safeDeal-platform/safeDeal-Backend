package com.safedeal.global.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Resend HTTP API 구현 (app.mail.provider=resend 일 때만 등록).
 *
 * 발송 실패를 예외로 올리지 않고 로그만 남기는 이유: 회원가입은 성공했는데 메일 게이트웨이가
 * 잠깐 죽었다고 가입 트랜잭션을 되돌리면 사용자는 계정도 못 만든다. 인증 메일은 재발송
 * 경로가 따로 있으므로(AUTH-6) 실패해도 복구 가능하다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.mail", name = "provider", havingValue = "resend")
public class ResendMailSender implements MailSender {

    private static final String ENDPOINT = "https://api.resend.com/emails";

    private final RestClient restClient;
    private final MailProperties properties;

    public ResendMailSender(MailProperties properties) {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new IllegalStateException("app.mail.provider=resend 인데 app.mail.api-key가 비어 있습니다.");
        }
        this.properties = properties;
        this.restClient = RestClient.builder()
                .baseUrl(ENDPOINT)
                .defaultHeader("Authorization", "Bearer " + properties.getApiKey())
                .build();
    }

    @Override
    public void send(String to, String subject, String body) {
        try {
            restClient.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "from", properties.getFrom(),
                            "to", new String[]{to},
                            "subject", subject,
                            "text", body))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException e) {
            // 주소·본문은 로그에 남기지 않는다(개인정보 + 재설정 링크 유출 방지).
            log.error("메일 발송 실패 - subject={} reason={}", subject, e.getClass().getSimpleName());
        }
    }
}
