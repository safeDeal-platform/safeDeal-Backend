package com.safedeal.global.util;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 메일 링크에 실어 보내는 1회용 토큰 생성기 (이메일 인증·비밀번호 재설정).
 *
 * URL-safe Base64를 쓰는 이유: 이 값이 쿼리 파라미터로 나가는데 표준 Base64의 +와 /는
 * URL에서 인코딩돼 원문이 달라지고, 그러면 해시 대조가 그냥 실패한다.
 *
 * 256비트를 쓰는 이유: 이 토큰 하나로 계정을 가져갈 수 있으므로(비밀번호 재설정) 추측이
 * 불가능해야 한다. UUID(122비트 랜덤)보다 여유를 둔다.
 */
public final class SecureToken {

    private static final int BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private SecureToken() {
    }

    public static String generate() {
        byte[] buffer = new byte[BYTES];
        RANDOM.nextBytes(buffer);
        return ENCODER.encodeToString(buffer);
    }
}
