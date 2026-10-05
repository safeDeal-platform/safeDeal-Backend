package com.safedeal.global.util;

import com.github.f4b6a3.ulid.UlidCreator;
import org.springframework.stereotype.Component;

/**
 * 외부 노출 식별자(public_id) 발급 — ULID는 앞 48비트가 시각이라 사전순이 곧 시간순이라서,
 * 난수 UUID와 달리 새 값이 인덱스 끝에만 쌓여 페이지 분할이 적다.
 *
 * 대가로 생성 시각이 값에 드러난다 — 매물·주문처럼 시각이 어차피 공개되는 대상에만 쓴다.
 * 빈으로 둔 이유는 테스트에서 고정 값을 주입하기 위해서다.
 */
@Component
public class PublicIdGenerator {

    /** ULID 문자열 길이. 스키마의 char(26)과 맞물린다. */
    public static final int LENGTH = 26;

    public String generate() {
        return UlidCreator.getMonotonicUlid().toString();
    }
}
