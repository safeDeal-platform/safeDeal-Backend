package com.safedeal.domain.user.entity;

// EnumType.STRING으로 저장한다(ORDINAL 금지 - 상수 순서가 바뀌면 기존 행의 의미가 통째로 바뀐다).
public enum UserRole {
    USER,
    ADMIN
}
