package com.safedeal.global.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 콘솔에 찍기만 하는 기본 구현. 로컬 개발·테스트용이다.
 *
 * 인증 링크를 로그로 볼 수 있어야 개발자가 메일 계정 없이도 이메일 인증·비밀번호 재설정
 * 흐름을 끝까지 확인할 수 있다. 실제 메일이 나가지 않으므로 운영에서는 절대 쓰지 않는다
 * (app.mail.provider를 resend로 두면 이 빈은 등록되지 않는다).
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
