package com.safedeal.domain.chat.service;

import org.hibernate.exception.ConstraintViolationException;

/**
 * "이 예외가 특정 UNIQUE 제약 위반인가"를 판정한다. 채팅의 재시도 서비스 두 곳이 같은 판정을
 * 쓰므로 원인 사슬을 훑는 미묘한 부분을 한 곳에 둔다.
 *
 * <p>메시지 문자열이 아니라 Hibernate가 구조화해 둔 제약 이름({@code getConstraintName()})을
 * 본다 — 메시지에는 사용자가 넣은 값(예: clientMessageId)이 그대로 실려, 값에 제약 이름을
 * 심어 판정을 속일 수 있다. MySQL은 제약 이름을 {@code 테이블.제약} 형태로 돌려주므로 접미
 * 일치로 비교한다. 제약 이름을 못 얻으면 <b>재시도하지 않는 쪽</b>으로 판정한다(원인을 모르는
 * 위반을 다시 시도해 원래 오류를 가리는 것보다 그대로 드러내는 편이 안전하다).
 */
final class UniqueViolations {

    private UniqueViolations() {
    }

    static boolean causedBy(Throwable error, String constraintName) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException violation
                    && matches(violation.getConstraintName(), constraintName)) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private static boolean matches(String actual, String expected) {
        if (actual == null) {
            return false;
        }
        return actual.equalsIgnoreCase(expected) || actual.toLowerCase().endsWith("." + expected.toLowerCase());
    }
}
