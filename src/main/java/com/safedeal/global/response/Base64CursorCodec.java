package com.safedeal.global.response;

import com.safedeal.global.exception.BusinessException;
import com.safedeal.global.exception.CommonErrorCode;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 커서를 JSON으로 직렬화해 URL-safe Base64로 감싼다(API 명세서 계약: {@code "eyJjIjoi..."} 형식).
 *
 * 커서는 비밀은 아니지만 신뢰하지도 않는다 — 클라이언트가 값을 고쳐 보낼 수 있어서, 조회는
 * 항상 공개 조건(ACTIVE·미삭제)을 다시 걸고 커서는 "어디서부터"만 정한다.
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
        if (payload == null) {
            // 본문이 JSON null이면(Base64로 "bnVsbA") Jackson은 예외 없이 자바 null을 돌려준다.
            // try 밖이라 아래 version() 호출이 NPE가 되고, 클라이언트 실수가 500으로 보고된다.
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
