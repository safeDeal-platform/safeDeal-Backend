package com.safedeal.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// 도메인에 속하지 않는 공통 에러 코드. 코드 접두어 C를 사용한다.
// 도메인별 에러 코드는 여기에 추가하지 말고 각자 domain/<도메인>/exception/XxxErrorCode를
// 만들어 ErrorCode를 구현할 것 (예: U001 = 회원, D001 = 거래 ...).
@Getter
@RequiredArgsConstructor
public enum CommonErrorCode implements ErrorCode {

    INVALID_INPUT(HttpStatus.BAD_REQUEST, "C001", "잘못된 입력값입니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "C002", "요청한 리소스를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "C003", "허용되지 않은 HTTP 메서드입니다."),
    CONFLICT(HttpStatus.CONFLICT, "C004", "요청이 현재 상태와 충돌합니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "C005", "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "C006", "접근 권한이 없습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "C007", "서버 내부 오류가 발생했습니다."),
    ;

    private final HttpStatus status;
    private final String code;
    private final String message;
}
