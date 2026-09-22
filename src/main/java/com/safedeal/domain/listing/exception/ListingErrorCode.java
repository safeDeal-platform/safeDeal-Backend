package com.safedeal.domain.listing.exception;

import com.safedeal.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 매물 도메인 에러 코드.
 *
 * <p>접두어 {@code LST}는 팀 규칙(단일 문자 금지 — A·P가 이미 충돌)을 따른다. 한 번 공개한
 * 코드는 바꾸지도 재사용하지도 않는다 — 클라이언트가 코드로 분기하기 시작하면 의미가 바뀌는
 * 순간 조용히 오동작한다.
 *
 * <p>공통 상황(잘못된 입력·권한 없음·낙관적 락 충돌)은 여기 두지 않고 {@code CommonErrorCode}를
 * 쓴다. 도메인 고유 사유만 남긴다.
 */
@Getter
@RequiredArgsConstructor
public enum ListingErrorCode implements ErrorCode {

    /** 반복해서 내렸다 올려 노출을 끌어올리는 어뷰징을 막는 하루 한도. */
    PRICE_DROP_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "LST001",
            "가격 인하는 하루 2회까지 가능합니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
