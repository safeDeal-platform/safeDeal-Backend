package com.safedeal.global.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {

    // ===== 공통 =====
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "C001", "잘못된 입력값입니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "C002", "요청한 리소스를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "C003", "허용되지 않은 HTTP 메서드입니다."),
    CONFLICT(HttpStatus.CONFLICT, "C004", "요청이 현재 상태와 충돌합니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "C005", "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "C006", "접근 권한이 없습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "C007", "서버 내부 오류가 발생했습니다."),

    // ===== 도메인별 코드는 담당자가 아래에 추가 (예: U001, D001 ...) =====
    ;

    private final HttpStatus status;
    private final String code;
    private final String message;

    ErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }
}
