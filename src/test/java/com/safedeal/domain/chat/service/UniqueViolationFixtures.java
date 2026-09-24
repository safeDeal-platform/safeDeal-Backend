package com.safedeal.domain.chat.service;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLIntegrityConstraintViolationException;

/**
 * 단위 테스트용 UNIQUE 위반 예외. 실제 MySQL이 돌려주는 모양 — Spring
 * {@link DataIntegrityViolationException} → Hibernate {@link ConstraintViolationException} →
 * 드라이버 {@code SQLIntegrityConstraintViolationException}(메시지가
 * {@code Duplicate entry '값' for key '테이블.제약'}) — 을 흉내 낸다. 이 모양이 실제와 같다는
 * 근거는 {@code UniqueViolationsTest}(진짜 MySQL)가 고정한다.
 */
final class UniqueViolationFixtures {

    private UniqueViolationFixtures() {
    }

    static DataIntegrityViolationException violationOf(String table, String constraintName) {
        String qualified = table + "." + constraintName;
        SQLIntegrityConstraintViolationException driver = new SQLIntegrityConstraintViolationException(
                "Duplicate entry '10-c-uuid-1' for key '" + qualified + "'");
        return new DataIntegrityViolationException(
                "could not execute statement",
                new ConstraintViolationException("could not execute statement", driver, "insert ...", qualified));
    }
}
