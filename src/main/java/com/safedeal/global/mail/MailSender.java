package com.safedeal.global.mail;

/**
 * 메일 발송 추상화.
 *
 * 인터페이스를 두는 이유: 이메일 인증(AUTH-6)·비밀번호 찾기(AUTH-7)의 진짜 로직은
 * 토큰 발급·해시 저장·TTL·1회용 검증이고 메일 전송은 마지막 한 줄이다. 발송 수단이
 * 정해지지 않았다고 해서 그 기능들을 못 만들면 안 되므로, local은 콘솔 로그 구현으로
 * 돌리고 운영만 실제 공급자로 갈아끼운다.
 *
 * MVP=Resend(도메인 없이 발송 가능·리드타임 0), AWS 배포 후 SES로 전환(정책 확정).
 */
public interface MailSender {

    void send(String to, String subject, String body);
}
