package com.safedeal.domain.user.entity;

/**
 * 계정 상태. 전이는 운영자 수동이며 신뢰도 점수가 자동으로 바꾸지 않는다(정책 TRS-6).
 *
 * SUSPENDED/BANNED 전이와 그에 딸린 매물 일괄 BLOCKED 처리는 신고·관리자 도메인 몫이다.
 */
public enum UserStatus {
    ACTIVE,
    SUSPENDED,
    BANNED
}
