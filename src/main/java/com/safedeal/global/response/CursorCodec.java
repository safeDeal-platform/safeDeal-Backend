package com.safedeal.global.response;

/**
 * 커서 문자열({@link CursorResponse#nextCursor()})과 {@link CursorPayload} 사이의 변환 계약.
 *
 * 인터페이스만 정의하고 구현체/빈 등록은 하지 않는다 — 인코딩 방식(AES-GCM 등으로 암호화한
 * opaque 토큰 vs Redis에 발급 이력을 저장하고 짧은 토큰만 내려주는 방식 vs 값 자체를
 * base64/공개 ID로만 인코딩해 재조회 시 재검증하는 방식)이 아직 정책으로 확정되지 않았다.
 * 정책이 정해지면 이 인터페이스의 구현체를 추가하고 빈으로 등록한다 — 그 전까지는 이
 * 인터페이스가 존재해도 구현체가 없으므로 빌드에는 영향이 없다.
 */
public interface CursorCodec {

    String encode(CursorPayload payload);

    CursorPayload decode(String cursor);
}
