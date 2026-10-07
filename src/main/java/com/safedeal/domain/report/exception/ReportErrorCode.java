package com.safedeal.domain.report.exception;

import com.safedeal.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 신고 도메인 에러 코드. 접두어 {@code RPT}는 팀 규칙(단일 문자 금지 — R은 Report·Review가 충돌)을
 * 따른다. 공개한 코드는 바꾸거나 재사용하지 않는다 — 의미가 바뀌면 클라이언트 분기가 조용히 틀어진다.
 *
 * <p>공통 상황(형식 오류, 대상 없음, 권한 없음)은 {@code CommonErrorCode}를 쓰고, 여기는 신고 고유 사유만 둔다.
 */
@Getter
@RequiredArgsConstructor
public enum ReportErrorCode implements ErrorCode {

    /** 같은 사람·대상·사유의 신고가 아직 접수·검토 중이다. 같은 요청의 중복 제출을 멱등으로 거절한다. */
    DUPLICATE_ACTIVE_REPORT(HttpStatus.CONFLICT, "RPT001",
            "이미 같은 사유로 접수된 신고가 있습니다."),

    /** 처리(RESOLVED)나 기각(REJECTED)이 끝난 신고와 같은 사유로는 다시 신고할 수 없다. */
    REPORT_ALREADY_CLOSED(HttpStatus.CONFLICT, "RPT002",
            "처리가 끝난 신고는 같은 사유로 다시 접수할 수 없습니다."),

    /** 신고자 1명 기준, 한국 날짜 하루 10건 한도. Redis 장애 시에는 걸리지 않는다(fail-open). */
    DAILY_REPORT_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "RPT003",
            "신고는 하루 10건까지 가능합니다."),

    /** 사유×대상 매트릭스 밖의 조합(예: 사기 사유를 채팅방 대상으로). 신고 자체가 거절된다. */
    REASON_NOT_ALLOWED_FOR_TARGET(HttpStatus.BAD_REQUEST, "RPT004",
            "이 대상에는 선택할 수 없는 신고 사유입니다."),

    /** 채팅 신고의 증거 메시지가 없거나, 다른 방 소속이거나, 신고자 본인 메시지다. */
    INVALID_EVIDENCE_MESSAGE(HttpStatus.BAD_REQUEST, "RPT005",
            "증거 메시지가 올바르지 않습니다."),

    /** 본인 매물은 본인이 신고할 수 없다. 예외를 열어 두면 처리 흐름이 꼬이므로 막는다. */
    SELF_REPORT_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "RPT006",
            "본인 매물은 신고할 수 없습니다."),

    /** 제재가 이미 끝난 대상(닫힌 채팅방 등)에 새 신고를 받지 않는다. */
    TARGET_ALREADY_SANCTIONED(HttpStatus.CONFLICT, "RPT007",
            "이미 제재가 끝난 대상입니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
