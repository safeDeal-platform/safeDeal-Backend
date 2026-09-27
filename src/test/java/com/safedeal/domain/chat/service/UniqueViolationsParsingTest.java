package com.safedeal.domain.chat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * {@link UniqueViolations}의 파싱 경계를 DB 없이 고정한다(실제 MySQL 형태는
 * {@code UniqueViolationsTest}가 본다).
 */
class UniqueViolationsParsingTest {

    private static Throwable driver(String message) {
        return new SQLIntegrityConstraintViolationException(message);
    }

    @Test
    @DisplayName("정상 메시지에서 꼬리의 제약 이름을 읽는다")
    void parsesConstraintFromTail() {
        Throwable e = driver("Duplicate entry '10-c1' for key 'chat_messages.uk_chat_messages_room_client_msg'");

        assertThat(UniqueViolations.violatedConstraint(e))
                .isEqualTo("chat_messages.uk_chat_messages_room_client_msg");
    }

    @Test
    @DisplayName("사용자 값에 구분자가 있어도 마지막(서버가 붙인) 구분자를 읽는다")
    void usesLastMarker_whenUserValueContainsDelimiter() {
        Throwable e = driver("Duplicate entry 'z' for key 'uk_other' for key 'tbl.uk_real'");

        assertThat(UniqueViolations.violatedConstraint(e)).isEqualTo("tbl.uk_real");
        assertThat(UniqueViolations.causedBy(e, "uk_real")).isTrue();
        assertThat(UniqueViolations.causedBy(e, "uk_other")).isFalse();
    }

    @Test
    @DisplayName("접두 일치가 아니라 '테이블.제약' 접미 또는 정확 일치만 인정한다")
    void matchesExactOrTableQualifiedSuffixOnly() {
        Throwable qualified = driver("Duplicate entry '1' for key 'tbl.uk_a'");
        Throwable bare = driver("Duplicate entry '1' for key 'uk_a'");
        Throwable lookalike = driver("Duplicate entry '1' for key 'tbl.xuk_a'");

        assertThat(UniqueViolations.causedBy(qualified, "uk_a")).isTrue();
        assertThat(UniqueViolations.causedBy(bare, "uk_a")).isTrue();
        assertThat(UniqueViolations.causedBy(lookalike, "uk_a")).isFalse();
    }

    @Test
    @DisplayName("구분자가 없거나 닫는 따옴표가 없거나 메시지가 null이면 읽지 못한 것(null)이라 재시도하지 않는다")
    void unreadableMessages_areFailSafe() {
        assertThat(UniqueViolations.violatedConstraint(driver("Data too long for column 'content'"))).isNull();
        assertThat(UniqueViolations.violatedConstraint(driver("Duplicate entry 'x' for key 'unterminated"))).isNull();
        assertThat(UniqueViolations.violatedConstraint(new RuntimeException((String) null))).isNull();
        assertThat(UniqueViolations.violatedConstraint(null)).isNull();
        assertThat(UniqueViolations.causedBy(driver("원인 불명"), "uk_a")).isFalse();
    }

    @Test
    @DisplayName("원인 사슬에 순환이 있어도 끝난다 (깊이 상한)")
    void cyclicCauseChain_terminates() {
        Throwable[] holder = new Throwable[2];
        holder[0] = new RuntimeException("a") {
            @Override
            public synchronized Throwable getCause() {
                return holder[1];
            }
        };
        holder[1] = new RuntimeException("b") {
            @Override
            public synchronized Throwable getCause() {
                return holder[0];
            }
        };

        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertThat(UniqueViolations.causedBy(holder[0], "uk_a")).isFalse());
    }
}
