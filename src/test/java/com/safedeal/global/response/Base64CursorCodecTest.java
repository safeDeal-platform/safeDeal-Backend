package com.safedeal.global.response;

import com.safedeal.global.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 커서 디코딩의 거부 분기를 고정한다.
 *
 * <p>커서는 <b>클라이언트가 고쳐 보낼 수 있는 값</b>이다(클래스 주석 참고). 그래서 여기서 중요한 것은
 * "정상 커서가 왕복하는가"보다 <b>망가진 입력이 전부 400으로 떨어지는가</b>다. 한 갈래라도 빠지면
 * 그 입력은 500이 되고, 클라이언트 실수가 서버 오류로 보고된다.
 */
class Base64CursorCodecTest {

    private final Base64CursorCodec codec = new Base64CursorCodec(new ObjectMapper());

    private String cursorOf(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("본문이 JSON null인 커서는 400으로 거부한다 — NPE로 새면 500이 된다")
    void nullPayloadIsRejected() {
        // Base64("null") = "bnVsbA". Jackson은 루트 JSON null을 자바 null로 돌려주므로
        // try 블록을 빠져나온 뒤 payload.version()에서 NPE가 난다 — catch가 RuntimeException을
        // 잡더라도 그 시점엔 이미 try 밖이라 BusinessException으로 변환되지 않는다.
        assertThatThrownBy(() -> codec.decode(cursorOf("null")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("커서");
    }

    @Test
    @DisplayName("Base64도 JSON도 아닌 커서는 400으로 거부한다")
    void garbageIsRejected() {
        assertThatThrownBy(() -> codec.decode("!!!not-base64!!!"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("커서");
    }

    @Test
    @DisplayName("포맷 버전이 다른 커서는 400으로 거부한다")
    void wrongVersionIsRejected() {
        assertThatThrownBy(() -> codec.decode(cursorOf("""
                {"version":99,"sort":"createdAt,desc","lastCreatedAt":null,
                 "lastId":null,"filterFingerprint":null,"issuedAt":null}""")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("커서");
    }

    @Test
    @DisplayName("keyset 기준값이 빠진 커서는 400으로 거부한다 — 없으면 조건이 통째로 빠진다")
    void missingKeysetFieldsAreRejected() {
        assertThatThrownBy(() -> codec.decode(cursorOf("""
                {"version":1,"sort":"createdAt,desc","lastCreatedAt":null,
                 "lastId":null,"filterFingerprint":null,"issuedAt":null}""")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("커서");
    }
}
