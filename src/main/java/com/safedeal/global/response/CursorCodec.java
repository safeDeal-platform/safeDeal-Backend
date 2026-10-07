package com.safedeal.global.response;

/**
 * 커서 문자열({@link CursorResponse#nextCursor()})과 {@link CursorPayload} 사이의 변환 계약.
 * 인코딩 방식이 아직 정책으로 정해지지 않아 구현체는 없다 — 정해지면 추가한다.
 */
public interface CursorCodec {

    String encode(CursorPayload payload);

    CursorPayload decode(String cursor);
}
