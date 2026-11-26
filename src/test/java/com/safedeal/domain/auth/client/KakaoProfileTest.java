package com.safedeal.domain.auth.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 카카오 이메일을 믿어도 되는지 판정 — 세 조건 중 하나라도 어긋나거나 비어 있으면 믿지 않는다. */
class KakaoProfileTest {

    private static KakaoProfile profile(Boolean needsAgreement, Boolean valid, Boolean verified, String email) {
        return new KakaoProfile(1L, new KakaoProfile.Account(needsAgreement, valid, verified, email));
    }

    @Test
    @DisplayName("동의함·유효·검증됨이 모두 참이면 이메일을 준다")
    void allConditionsMet() {
        assertThat(profile(false, true, true, "a@kakao.com").verifiedEmail()).contains("a@kakao.com");
    }

    @Test
    @DisplayName("조건 하나라도 어긋나거나 값이 없으면 이메일을 주지 않는다")
    void anyConditionFails() {
        assertThat(profile(true, true, true, "a@kakao.com").verifiedEmail()).isEmpty();   // 미동의
        assertThat(profile(false, false, true, "a@kakao.com").verifiedEmail()).isEmpty(); // 무효
        assertThat(profile(false, true, false, "a@kakao.com").verifiedEmail()).isEmpty(); // 미검증
        assertThat(profile(null, true, true, "a@kakao.com").verifiedEmail()).isEmpty();   // 동의 여부 없음
        assertThat(profile(false, null, true, "a@kakao.com").verifiedEmail()).isEmpty();
        assertThat(profile(false, true, null, "a@kakao.com").verifiedEmail()).isEmpty();
        assertThat(profile(false, true, true, null).verifiedEmail()).isEmpty();
        assertThat(profile(false, true, true, " ").verifiedEmail()).isEmpty();
        assertThat(new KakaoProfile(1L, null).verifiedEmail()).isEmpty();                  // kakao_account 없음
    }
}
