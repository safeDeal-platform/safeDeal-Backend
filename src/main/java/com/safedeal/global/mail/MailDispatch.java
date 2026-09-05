package com.safedeal.global.mail;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 트랜잭션이 <b>커밋된 뒤에</b> 메일을 보낸다.
 *
 * 트랜잭션 안에서 그냥 보내면, 뒤에서 커밋이 실패했을 때 이미 나간 메일을 되돌릴 수 없다.
 * 가입이 롤백됐는데 인증 메일은 도착하는 식이라, 사용자는 존재하지 않는 계정의 링크를 받는다.
 * 반대 순서(커밋 후 발송)면 최악이 "가입은 됐는데 메일이 안 왔다"이고, 그건 재발송으로 복구된다.
 *
 * 발송 실패를 예외로 올리지 않는 것과 같은 판단이다 — 메일은 곁가지고 계정 생성이 본체다.
 *
 * 트랜잭션 밖에서 부르면 그냥 즉시 보낸다(테스트·배치 경로).
 */
public final class MailDispatch {

    private MailDispatch() {
    }

    public static void afterCommit(MailSender mailSender, String to, String subject, String body) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            mailSender.send(to, subject, body);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                mailSender.send(to, subject, body);
            }
        });
    }
}
