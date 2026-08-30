package com.safedeal.global.response;

import com.safedeal.global.exception.BusinessException;
import com.safedeal.global.exception.CommonErrorCode;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 커서를 JSON으로 직렬화한 뒤 URL-safe Base64로 감싸는 구현.
 *
 * <p>{@link CursorCodec} 주석이 남겨둔 세 후보(암호화 opaque 토큰 / Redis 발급 이력 / 값 인코딩)
 * 중 마지막이다. API 명세서의 응답 예시가 {@code "nextCursor": "eyJjIjoi..."} — Base64로 감싼
 * JSON이라 그 계약을 따른다.
 *
 * <p><b>커서는 비밀이 아니다.</b> 디코딩하면 마지막 항목의 생성 시각과 id가 보인다. 그 두 값은
 * 목록 응답에 이미 들어 있는 정보라 새로 새는 것이 없다. 대신 <b>커서를 신뢰하지는 않는다</b> —
 * 클라이언트가 값을 고쳐 보낼 수 있으므로, 조회는 언제나 공개 조건(ACTIVE·미삭제)을 다시 걸고
 * 커서는 "어디서부터"만 정한다.
 *
 * <p>버전과 정렬 기준을 함께 실어, 포맷이 바뀌거나 정렬을 바꾼 채 옛 커서를 재사용하면 거부한다.
 */
@Component
public class Base64CursorCodec implements CursorCodec {

    /** 커서 포맷 버전. 구조가 바뀌면 올리고, 다른 버전은 거부한다. */
    public static final int VERSION = 1;

    private final ObjectMapper objectMapper;

    public Base64CursorCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String encode(CursorPayload payload) {
        byte[] json = objectMapper.writeValueAsBytes(payload);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
    }

    @Override
    public CursorPayload decode(String cursor) {
        CursorPayload payload;
        try {
            byte[] json = Base64.getUrlDecoder().decode(cursor);
            payload = objectMapper.readValue(new String(json, StandardCharsets.UTF_8),
                    CursorPayload.class);
        } catch (RuntimeException e) {
            // 원인 문자열을 그대로 돌려주지 않는다 — 잘못된 커서는 클라이언트 실수이고,
            // 파서 예외 메시지는 내부 구조를 드러낸다.
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, "잘못된 커서입니다.");
        }
        if (payload.version() != VERSION) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, "지원하지 않는 커서입니다.");
        }
        if (payload.lastCreatedAt() == null || payload.lastId() == null) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, "잘못된 커서입니다.");
        }
        return payload;
    }
}
