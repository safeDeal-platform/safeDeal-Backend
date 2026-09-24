package com.safedeal.domain.chat.service;

/**
 * "이 예외가 특정 UNIQUE 제약 위반인가"를 판정한다. 채팅의 재시도 서비스 두 곳이 같은 판정을
 * 쓰므로 미묘한 부분을 한 곳에 둔다.
 *
 * <p><b>왜 Hibernate의 {@code getConstraintName()}을 안 쓰나:</b> MySQL 드라이버의 메시지는
 * {@code Duplicate entry '<위반한 값>' for key '<테이블.제약>'} 형태다. 앞쪽 {@code <위반한 값>}에는
 * 사용자가 넣은 문자열(예: clientMessageId)이 그대로 실린다. Hibernate의 MySQL 추출기는
 * {@code " for key '"}의 <b>첫</b> 출현을 잘라 이름으로 쓰므로(Hibernate 7.2.12 소스로 확인),
 * clientMessageId에 그 구분자를 심으면 엉뚱한 이름이 나온다. 서버가 붙이는 <b>꼬리</b>는 사용자
 * 값이 바꿀 수 없으므로 <b>마지막</b> 출현을 읽는다.
 *
 * <p>가장 깊은 원인(드라이버 예외)의 메시지만 본다 — 위쪽 래퍼 예외 메시지에는 SQL 문 등이 더
 * 실려 있다. 원인 사슬은 깊이를 제한해 순환이 있어도 끝난다.
 *
 * <p><b>한계(숨기지 않는다):</b> MySQL 8 메시지 형식에 의존한다. 형식을 못 읽으면 <b>재시도하지
 * 않는 쪽</b>(fail-safe)으로 판정한다 — 원인을 모르는 위반을 다시 시도해 원래 오류를 가리는 것보다
 * 그대로 드러내는 편이 안전하다. 그 대가는 경쟁에서 진 요청이 재시도 없이 409를 받는 것이다.
 * 실제 MySQL이 돌려주는 이름 형태는 {@code UniqueViolationsTest}가 고정한다.
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
