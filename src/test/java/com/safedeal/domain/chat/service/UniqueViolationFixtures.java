package com.safedeal.domain.chat.service;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

/**
 * 단위 테스트용 UNIQUE 위반 예외. 실제 MySQL이 돌려주는 모양(제약 이름이 {@code 테이블.제약}
 * 형태로 실린 Hibernate {@link ConstraintViolationException}을 원인으로 가진
 * {@link DataIntegrityViolationException})을 흉내 낸다 — 이 모양이 실제와 같다는 것은
 * {@code UniqueViolationsTest}가 진짜 MySQL로 고정한다.
 */
final class UniqueViolationFixtures {

    private UniqueViolationFixtures() {
    }

    static DataIntegrityViolationException violationOf(String table, String constraintName) {
        String qualified = table + "." + constraintName;
        SQLException sql = new SQLException("Duplicate entry for key '" + qualified + "'");
        return new DataIntegrityViolationException(
                "could not execute statement",
                new ConstraintViolationException("could not execute statement", sql, "insert ...", qualified));
    }
}
