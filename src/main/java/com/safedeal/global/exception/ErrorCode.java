package com.safedeal.global.exception;

import org.springframework.http.HttpStatus;

// 모든 에러 코드가 구현하는 계약. 공통 에러는 CommonErrorCode가, 도메인별 에러는
// domain/<도메인>/exception/XxxErrorCode enum이 구현한다 — enum 하나에 다 몰아넣으면
// 여러 명이 동시에 코드를 추가할 때 같은 파일을 고쳐서 머지 충돌이 잦아진다.
public interface ErrorCode {

    HttpStatus getStatus();

    String getCode();

    String getMessage();
}
