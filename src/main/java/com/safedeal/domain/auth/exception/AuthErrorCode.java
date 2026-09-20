package com.safedeal.domain.auth.exception;

import com.safedeal.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 인증 도메인 에러 코드.
 *
 * 접두어를 요구사항 명세서의 ID(AUTH-1 등)와 맞춰 AUTH로 쓴다. 도메인이 14개라 첫 글자
 * 한 자로는 겹친다 — Auth-Admin, Payment-Price, Trust-Transaction, Report-Review,
 * Common-Chat. 명세서 ID를 그대로 쓰면 문서에서 코드를 역추적하기도 쉽다.
 *
 * 한 번 응답에 실려 나간 코드는 변경·재사용이 금지된다(공통 규칙). 뒤에 이어지는 기능은
 * 번호를 이어서 쓰고 중간 번호를 재사용하지 않는다.
 */
@Getter
@RequiredArgsConstructor
public enum AuthErrorCode implements ErrorCode {

    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "AUTH001", "이미 사용 중인 이메일입니다."),
    DUPLICATE_NICKNAME(HttpStatus.CONFLICT, "AUTH002", "이미 사용 중인 닉네임입니다."),
    // 이메일이 없는 경우와 비밀번호가 틀린 경우를 구분하지 않는다 - 구분하면 응답만 보고
    // "이 이메일은 가입돼 있다"를 확인할 수 있어 계정 열거 통로가 된다.
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "AUTH003", "이메일 또는 비밀번호가 올바르지 않습니다."),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH004", "유효하지 않거나 만료된 토큰입니다."),
    ACCOUNT_NOT_ACTIVE(HttpStatus.FORBIDDEN, "AUTH005", "이용이 제한된 계정입니다."),
    // 잠금 사유를 그대로 알려주면 공격자가 임계값을 역산할 수 있어 메시지는 뭉뚱그린다.
    TOO_MANY_LOGIN_ATTEMPTS(HttpStatus.TOO_MANY_REQUESTS, "AUTH006",
            "로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요."),
    // refresh 쿠키가 아예 없는 경우. INVALID_TOKEN과 나누는 이유는 프런트가 "로그인 화면으로"와
    // "재시도"를 구분해야 하기 때문이다.
    REFRESH_TOKEN_MISSING(HttpStatus.UNAUTHORIZED, "AUTH007", "인증 정보가 없습니다. 다시 로그인해 주세요."),
    // 만료·이미 사용됨·존재하지 않음을 하나로 묶는다. 구분해 주면 "이 토큰은 있는데 만료됐다"가
    // 새어 토큰 추측에 힌트가 된다.
    INVALID_EMAIL_VERIFICATION_TOKEN(HttpStatus.BAD_REQUEST, "AUTH008",
            "인증 링크가 유효하지 않거나 만료되었습니다."),
    // 재설정 토큰도 같은 이유로 만료·사용됨·없음을 구분하지 않는다.
    INVALID_PASSWORD_RESET_TOKEN(HttpStatus.BAD_REQUEST, "AUTH009",
            "재설정 링크가 유효하지 않거나 만료되었습니다."),
    // 메일을 유발하는 요청(재설정 링크 요청·인증 메일 재발송)의 호출 제한.
    // 로그인 잠금(AUTH006)과 나누는 이유는 원인도 안내 문구도 다르기 때문이다.
    TOO_MANY_MAIL_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "AUTH010",
            "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."),
    // AUTH011 이후는 OAuth(AUTH-5)에서 이어서 쓴다.
    ;

    private final HttpStatus status;
    private final String code;
    private final String message;
}
