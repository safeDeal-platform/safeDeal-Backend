package com.safedeal.domain.auth.entity;

/**
 * 소셜 로그인 공급자 (정책 AUTH-5). 지금은 카카오만 있다 — 구글은 D-20으로 미뤘다.
 *
 * EnumType.STRING으로 저장한다 — ORDINAL로 두면 상수 순서가 바뀌는 순간 기존 행의 의미가
 * 통째로 바뀐다(공통 규칙). 그래서 나중에 GOOGLE을 추가해도 기존 행은 안전하다.
 */
public enum OauthProvider {
    KAKAO
}
