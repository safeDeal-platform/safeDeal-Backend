package com.safedeal.global.util;

import java.util.Locale;

/**
 * 계정 조회·레이트리밋 키에 쓰기 전에 이메일을 trim + 소문자로 맞춘다.
 *
 * 왜 필요한가: DB 콜레이션(utf8mb4_0900_ai_ci, 정책 'DB 기초 규칙')은 대소문자를 무시해 같은
 * 계정을 찾아주지만, 레이트리밋 한도(MailSendRateLimiter)·로그인 실패 카운터(LoginAttemptStore)의
 * 키는 문자열을 그대로 비교한다. 대소문자만 바꿔 요청하면 한도가 계정당이 아니라 문자열당으로
 * 쪼개져, 그 한도를 우회해 같은 계정을 계속 두드릴 수 있다 — 이 함수가 그 간극을 없앤다.
 *
 * 범위 밖(D-17): 이미 저장된 users.email의 대소문자는 정리하지 않는다. 범위 밖(D-18): 악센트
 * (발음 구별 기호) 변형은 이 함수로 막히지 않는다 — docs/V2-deferred.md 참고.
 */
public final class EmailNormalizer {

    private EmailNormalizer() {
    }

    public static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
