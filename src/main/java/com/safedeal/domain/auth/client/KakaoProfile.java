package com.safedeal.domain.auth.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Optional;

/**
 * 카카오 사용자 정보(/v2/user/me) 중 우리가 쓰는 부분.
 *
 * 응답 예시는 docs/AUTH-5-oauth-준비.md 「사용자 정보 응답」. 모르는 필드는 무시한다 —
 * 카카오가 필드를 추가할 때마다 로그인이 깨지면 안 된다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KakaoProfile(Long id, @JsonProperty("kakao_account") Account account) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Account(
            @JsonProperty("email_needs_agreement") Boolean emailNeedsAgreement,
            @JsonProperty("is_email_valid") Boolean emailValid,
            @JsonProperty("is_email_verified") Boolean emailVerified,
            String email) {
    }

    /**
     * 믿을 수 있는 이메일만 돌려준다. 세 조건을 모두 봐야 한다 — 하나만 보면 뚫린다(정책 사본 2장).
     * <ul>
     *   <li>동의함(email_needs_agreement=false) — 동의 전이면 email 필드 자체가 없다</li>
     *   <li>유효함(is_email_valid=true) — 무효 주소는 일부가 가려진 채로 온다</li>
     *   <li>카카오가 검증함(is_email_verified=true)</li>
     * </ul>
     * 값이 빠져 있으면(null) 거짓으로 본다 — 애매하면 막는다(2026-11-19 결정: 미인증이면 가입 거부).
     */
    public Optional<String> verifiedEmail() {
        if (account == null
                || !Boolean.FALSE.equals(account.emailNeedsAgreement())
                || !Boolean.TRUE.equals(account.emailValid())
                || !Boolean.TRUE.equals(account.emailVerified())
                || account.email() == null
                || account.email().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(account.email());
    }
}
