package com.safedeal.global.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 콘솔에만 찍는 로컬용 구현 — 메일 계정 없이도 인증 링크를 확인하려고 있다.
 * provider=resend면 이 빈은 아예 등록되지 않는다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.mail", name = "provider", havingValue = "log", matchIfMissing = true)
public class LoggingMailSender implements MailSender {

    @Override
    public void send(String to, String subject, String body) {
        // 수신자 주소는 개인정보라 로컬 전용 구현에서만 찍는다.
        log.info("[메일-로컬] to={} subject={}\n{}", to, subject, body);
    }
}
