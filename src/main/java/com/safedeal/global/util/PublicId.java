package com.safedeal.global.util;

import java.security.SecureRandom;

/**
 * 외부 노출용 식별자(public_id) 생성기 — 내부 PK를 그대로 노출하면 전체 개수·증가 속도 같은
 * 정보가 새고, UUIDv4는 정렬성이 없어 인덱스가 흩어진다. ULID는 앞 48비트가 생성 시각이라
 * 문자열 정렬이 곧 생성 순서면서 값 자체는 추측할 수 없다.
 *
 * 라이브러리 없이 직접 구현한다 — 필요한 건 생성 하나뿐이라 26자 인코딩 때문에 의존성을
 * 추가하지 않았다(파싱·모노토닉이 필요해지면 교체). 인코딩은 Crockford Base32 —
 * 사람이 옮겨 적을 때 헷갈리는 I·L·O·U를 알파벳에서 뺐다.
 */
public final class PublicId {

    // Crockford Base32. I·L·O·U 제외 (0/O, 1/I/L 혼동 방지)
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    // ULID = 48비트 타임스탬프(10자) + 80비트 랜덤(16자) = 26자.
    // 랜덤 80비트는 long 하나에 안 들어가므로 40비트씩 둘로 나눈다(각각 정확히 8자).
    private static final int TIMESTAMP_CHARS = 10;
    private static final int RANDOM_HALF_CHARS = 8;
    private static final long FORTY_BIT_MASK = 0xFF_FFFF_FFFFL;
    private static final int FIVE_BIT_MASK = 0x1F;

    public static final int LENGTH = 26;

    private static final SecureRandom RANDOM = new SecureRandom();

    private PublicId() {
    }

    public static String generate() {
        char[] out = new char[LENGTH];
        writeBase32(out, 0, System.currentTimeMillis(), TIMESTAMP_CHARS);
        writeBase32(out, TIMESTAMP_CHARS, RANDOM.nextLong() & FORTY_BIT_MASK, RANDOM_HALF_CHARS);
        writeBase32(out, TIMESTAMP_CHARS + RANDOM_HALF_CHARS, RANDOM.nextLong() & FORTY_BIT_MASK, RANDOM_HALF_CHARS);
        return new String(out);
    }

    // value의 하위 (chars * 5)비트를 뒤에서부터 5비트씩 잘라 채운다.
    private static void writeBase32(char[] out, int offset, long value, int chars) {
        for (int i = chars - 1; i >= 0; i--) {
            out[offset + i] = ALPHABET[(int) (value & FIVE_BIT_MASK)];
            value >>>= 5;
        }
    }
}
