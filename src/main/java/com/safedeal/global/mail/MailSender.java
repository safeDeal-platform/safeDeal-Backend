package com.safedeal.global.mail;

/**
 * 메일 발송 추상화. 이메일 인증(AUTH-6)·비밀번호 찾기(AUTH-7)의 핵심은 토큰 로직이라
 * local은 로그로 찍고 운영은 실제 공급자로, 구현만 갈아끼운다(MVP=Resend, 이후 SES로 전환 예정).
 */
public interface MailSender {

    void send(String to, String subject, String body);
}
