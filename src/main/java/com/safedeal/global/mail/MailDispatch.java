package com.safedeal.global.mail;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 트랜잭션이 커밋된 뒤에 메일을 보낸다 — 커밋 전에 보내면 가입이 롤백돼도 메일은 이미
 * 나간 뒤라 없는 계정의 링크가 도착한다. 트랜잭션 밖에서 부르면 즉시 보낸다.
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
