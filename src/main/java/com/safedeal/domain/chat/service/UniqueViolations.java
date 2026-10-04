package com.safedeal.domain.chat.service;

/**
 * "이 예외가 특정 UNIQUE 제약 위반인가"를 판정한다.
 *
 * <p>Hibernate의 {@code getConstraintName()}을 쓰지 않는다 — MySQL 오류 메시지
 * ("Duplicate entry '사용자 값' for key '제약이름'")에서 그 함수는 <b>첫 번째</b> 구분자를
 * 잘라 쓰는데, 사용자가 넣은 값(clientMessageId 등)에 같은 구분자 문자열을 심으면 엉뚱한
 * 제약 이름이 나온다. 그래서 서버가 맨 뒤에 붙이는 진짜 제약 이름을 읽으려고 <b>마지막</b>
 * 구분자를 찾는다.
 *
 * <p>이 형식을 못 읽으면 재시도하지 않고 그대로 실패시킨다 — 원인을 모른 채 다시 시도하면
 * 진짜 오류가 가려질 수 있다.
 */
final class UniqueViolations {

    private static final String KEY_MARKER = " for key '";

    /** 순환 방어용 상한. 실제 사슬은 3~4단이다. */
    private static final int MAX_CAUSE_DEPTH = 32;

    private UniqueViolations() {
    }

    /** 위반된 제약의 이름(MySQL 8은 {@code 테이블.제약}). 못 읽으면 null. */
    static String violatedConstraint(Throwable error) {
        Throwable root = deepestCause(error);
        String message = root == null ? null : root.getMessage();
        if (message == null) {
            return null;
        }
        int marker = message.lastIndexOf(KEY_MARKER);
        if (marker < 0) {
            return null;
        }
        int start = marker + KEY_MARKER.length();
        int end = message.indexOf('\'', start);
        if (end < 0) {
            return null;
        }
        return message.substring(start, end);
    }

    static boolean causedBy(Throwable error, String constraintName) {
        String actual = violatedConstraint(error);
        if (actual == null) {
            return false;
        }
        return actual.equalsIgnoreCase(constraintName)
                || actual.toLowerCase().endsWith("." + constraintName.toLowerCase());
    }

    private static Throwable deepestCause(Throwable error) {
        Throwable current = error;
        for (int depth = 0; depth < MAX_CAUSE_DEPTH && current != null && current.getCause() != null; depth++) {
            current = current.getCause();
        }
        return current;
    }
}
