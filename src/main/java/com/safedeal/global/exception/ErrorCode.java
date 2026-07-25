package com.safedeal.global.exception;

import org.springframework.http.HttpStatus;

// 모든 에러 코드가 구현하는 계약.
// 공통 에러는 CommonErrorCode가 구현하고, 도메인별 에러는 담당자가
// domain/<도메인>/exception/XxxErrorCode enum을 만들어 이 인터페이스를 구현한다.
// (enum 하나에 코드를 몰아 넣지 않는 이유: 팀원 여러 명이 동시에 도메인 코드를
//  추가할 때 같은 파일을 건드리게 되어 머지 충돌이 잦아지기 때문)
public interface ErrorCode {

    HttpStatus getStatus();

    String getCode();

    String getMessage();
}
