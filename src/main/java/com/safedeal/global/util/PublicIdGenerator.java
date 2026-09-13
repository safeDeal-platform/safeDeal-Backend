package com.safedeal.global.util;

import com.github.f4b6a3.ulid.UlidCreator;
import org.springframework.stereotype.Component;

/**
 * 외부 노출 식별자(public_id) 발급.
 *
 * <p>ULID를 쓰는 이유는 인덱스 지역성이다. public_id는 UNIQUE 세컨더리 인덱스인데 값이 난수면
 * INSERT마다 인덱스 곳곳에 흩어져 꽂혀 페이지 분할이 잦아진다. ULID는 앞 48비트가 시각이라
 * 사전순이 곧 시간순이고, 새 값이 인덱스 끝에 붙는다.
 *
 * <p>대가로 생성 시각이 값에 드러난다. 매물·주문처럼 생성 시각이 어차피 공개되는 대상에만 쓰고,
 * 시각을 숨겨야 하는 곳에는 쓰지 않는다.
 *
 * <p>빈으로 두는 이유: 테스트에서 고정 값을 주입해 결과를 단정할 수 있어야 한다.
 */
@Component
public class PublicIdGenerator {

    /** ULID 문자열 길이. 스키마의 char(26)과 맞물린다. */
    public static final int LENGTH = 26;

    public String generate() {
        return UlidCreator.getMonotonicUlid().toString();
    }
}
