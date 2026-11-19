package com.safedeal.domain.auth.exception;

import com.safedeal.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 인증 도메인 에러 코드. 접두어는 AUTH — 도메인이 14개라 첫 글자 하나로는 겹친다
 * (Auth-Admin, Payment-Price 등). 한 번 나간 코드는 변경·재사용 금지, 번호는 이어서만 쓴다.
 */
@Getter
@RequiredArgsConstructor
public enum AuthErrorCode implements ErrorCode {

    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "AUTH001", "이미 사용 중인 이메일입니다."),
    DUPLICATE_NICKNAME(HttpStatus.CONFLICT, "AUTH002", "이미 사용 중인 닉네임입니다."),
    // 이메일이 없는 경우와 비밀번호 틀림을 구분하지 않는다 — 구분하면 가입 여부를 알아낼 수 있다.
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "AUTH003", "이메일 또는 비밀번호가 올바르지 않습니다."),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH004", "유효하지 않거나 만료된 토큰입니다."),
    ACCOUNT_NOT_ACTIVE(HttpStatus.FORBIDDEN, "AUTH005", "이용이 제한된 계정입니다."),
    // 잠금 사유를 그대로 알려주면 공격자가 임계값을 역산할 수 있어 메시지는 뭉뚱그린다.
    TOO_MANY_LOGIN_ATTEMPTS(HttpStatus.TOO_MANY_REQUESTS, "AUTH006",
            "로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요."),
    // refresh 쿠키 자체가 없는 경우. INVALID_TOKEN과 나누는 이유는 프런트가 "로그인 화면으로"와 "재시도"를 구분해야 해서다.
    REFRESH_TOKEN_MISSING(HttpStatus.UNAUTHORIZED, "AUTH007", "인증 정보가 없습니다. 다시 로그인해 주세요."),
    // 만료·이미 사용됨·존재하지 않음을 하나로 묶는다 — 구분해주면 토큰 추측에 힌트를 준다.
    INVALID_EMAIL_VERIFICATION_TOKEN(HttpStatus.BAD_REQUEST, "AUTH008",
            "인증 링크가 유효하지 않거나 만료되었습니다."),
    // 재설정 토큰도 같은 이유로 만료·사용됨·없음을 구분하지 않는다.
    INVALID_PASSWORD_RESET_TOKEN(HttpStatus.BAD_REQUEST, "AUTH009",
            "재설정 링크가 유효하지 않거나 만료되었습니다."),
    // 메일을 보내는 요청(재설정 링크·인증 메일 재발송)의 호출 제한 — 로그인 잠금(AUTH006)과 원인이 달라 코드도 나눈다.
    TOO_MANY_MAIL_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "AUTH010",
            "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."),
    // ── 카카오 로그인 (AUTH-5) ──
    // state 불일치·재사용·만료·누락을 구분하지 않는다 — 구분해주면 위조 시도에 힌트를 준다.
    OAUTH_INVALID_STATE(HttpStatus.BAD_REQUEST, "AUTH011",
            "로그인 요청이 유효하지 않거나 만료되었습니다. 다시 시도해 주세요."),
    OAUTH_PROVIDER_FAILED(HttpStatus.BAD_GATEWAY, "AUTH012",
            "카카오 로그인 처리 중 문제가 발생했습니다. 잠시 후 다시 시도해 주세요."),
    // 동의 화면에서 취소한 경우. 오류(AUTH012)와 나누는 이유는 프런트가 "취소하셨어요"를 따로 보여줘야 해서다.
    OAUTH_CANCELLED(HttpStatus.BAD_REQUEST, "AUTH013", "카카오 로그인이 취소되었습니다."),
    // 카카오 이메일이 없음·무효·미검증 — 가입을 받지 않는다(2026-11-19 결정, users.email NOT NULL 유지).
    OAUTH_EMAIL_REQUIRED(HttpStatus.BAD_REQUEST, "AUTH014",
            "카카오 계정의 이메일 인증이 필요합니다. 카카오에서 이메일을 인증한 뒤 다시 시도해 주세요."),
    // 같은 이메일의 계정이 있지만 자동 연결할 수 없는 경우(그 계정이 이메일 미인증 / 이미 다른 카카오와 연결 /
    // 탈퇴한 계정이 이메일을 점유). 미인증 계정에 붙이면 남의 이메일로 먼저 가입한 사람이 계정을 가로챈다.
    OAUTH_LINK_UNAVAILABLE(HttpStatus.CONFLICT, "AUTH015",
            "이 이메일로 가입된 계정과 카카오 계정을 연결할 수 없습니다. 기존 계정으로 로그인해 주세요."),
    // 가입 티켓 없음·만료·사용됨을 구분하지 않는다(AUTH008과 같은 이유).
    INVALID_OAUTH_SIGNUP_TICKET(HttpStatus.BAD_REQUEST, "AUTH016",
            "가입 요청이 만료되었습니다. 카카오 로그인을 다시 진행해 주세요."),
    ;

    private final HttpStatus status;
    private final String code;
    private final String message;
}
