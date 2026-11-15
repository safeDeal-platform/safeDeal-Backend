package com.safedeal.global.util;

/**
 * "이 예외가 특정 UNIQUE 제약 위반인가"를 판정한다. 원래 채팅 도메인에만 있었으나(재시도
 * 서비스 두 곳이 같은 판정을 썼다), 신고 도메인도 같은 판정이 필요해 여기로 승격했다 — 판정
 * 로직 자체는 특정 도메인의 제약이 아니라 MySQL 예외 모양에 대한 것이라 도메인에 묶일 이유가
 * 없다.
 *
 * <p>Hibernate의 {@code getConstraintName()}을 쓰지 않는다 — MySQL 오류 메시지
 * ("Duplicate entry '사용자 값' for key '제약이름'")에서 그 함수는 <b>첫 번째</b> 구분자를
 * 잘라 쓰는데, 사용자가 넣은 값(clientMessageId 등)에 같은 구분자 문자열을 심으면 엉뚱한
 * 제약 이름이 나온다. 그래서 서버가 맨 뒤에 붙이는 진짜 제약 이름을 읽으려고 <b>마지막</b>
 * 구분자를 찾는다.
 *
 * <p>가장 깊은 원인(드라이버 예외)의 메시지만 본다 — 위쪽 래퍼 예외 메시지에는 SQL 문 등이 더
 * 실려 있다. 원인 사슬은 깊이를 제한해 순환이 있어도 끝난다.
 *
 * <p><b>한계(숨기지 않는다):</b> MySQL 8 메시지 형식에 의존한다. 형식을 못 읽으면 <b>재시도하지
 * 않는 쪽</b>(fail-safe)으로 판정한다 — 원인을 모르는 위반을 다시 시도해 원래 오류를 가리는 것보다
 * 그대로 드러내는 편이 안전하다. 그 대가는 경쟁에서 진 요청이 재시도 없이 409를 받는 것이다.
 * 실제 MySQL이 돌려주는 이름 형태는 {@link com.safedeal.domain.chat.service.UniqueViolationsTest}
 * (실제 MySQL)와 {@link UniqueViolationsParsingTest}(DB 없이 파싱 경계)가 고정한다.
 */
public final class UniqueViolations {

    private static final String KEY_MARKER = " for key '";

    /** 순환 방어용 상한. 실제 사슬은 3~4단이다. */
    private static final int MAX_CAUSE_DEPTH = 32;

    private UniqueViolations() {
    }

    /** 위반된 제약의 이름(MySQL 8은 {@code 테이블.제약}). 못 읽으면 null. */
    public static String violatedConstraint(Throwable error) {
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

    public static boolean causedBy(Throwable error, String constraintName) {
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
