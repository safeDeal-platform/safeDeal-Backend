package com.safedeal.testsupport;

import com.safedeal.global.mail.MailSender;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 테스트에서 발송된 메일을 들여다보기 위한 MailSender.
 *
 * 이메일 인증·비밀번호 재설정 토큰은 <b>해시로만 저장</b>되므로 DB에서 원문을 되찾을 수 없다.
 * 실제 사용자와 같은 경로 — 메일 본문의 링크에서 토큰을 읽어 API에 넣는 방식 — 으로
 * 검증하려면 발송 내용을 잡아둘 곳이 필요하다.
 *
 * {@code @Primary}로 LoggingMailSender를 밀어낸다. 프로파일이나 프로퍼티로 가르지 않는 이유는
 * 테스트 소스에만 있는 빈이라 운영 컨텍스트에는 애초에 올라올 수 없기 때문이다.
 */
@Primary
@Component
public class RecordingMailSender implements MailSender {

    public record SentMail(String to, String subject, String body) {
    }

    private final List<SentMail> sent = new ArrayList<>();

    @Override
    public synchronized void send(String to, String subject, String body) {
        sent.add(new SentMail(to, subject, body));
    }

    public synchronized void clear() {
        sent.clear();
    }

    public synchronized List<SentMail> sentTo(String email) {
        return sent.stream().filter(mail -> mail.to().equals(email)).toList();
    }

    public synchronized Optional<SentMail> lastTo(String email) {
        List<SentMail> matches = sentTo(email);
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(matches.size() - 1));
    }

    /** 메일 본문 링크의 ?token= 뒤를 그대로 돌려준다. 사용자가 링크를 누르는 것과 같은 경로다. */
    public synchronized String tokenFromLastMailTo(String email) {
        String body = lastTo(email)
                .orElseThrow(() -> new AssertionError(email + " 앞으로 발송된 메일이 없다"))
                .body();
        int marker = body.indexOf("?token=");
        if (marker < 0) {
            throw new AssertionError("메일 본문에 토큰 링크가 없다: " + body);
        }
        return body.substring(marker + "?token=".length()).trim();
    }
}
