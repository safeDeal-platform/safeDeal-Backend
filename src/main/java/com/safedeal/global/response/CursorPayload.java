package com.safedeal.global.response;

import java.time.Instant;

/**
 * {@link CursorCodec}가 인코딩/디코딩하는 커서의 논리적 내용.
 *
 * @param version           커서 포맷 버전. 인코딩 방식이 바뀌어도 구 버전 커서를 구분/거부할 수 있게 한다.
 * @param sort              이 커서가 속한 정렬 기준 식별자 (예: "createdAt,desc"). 클라이언트가 정렬을
 *                          바꿔서 요청했는데 이전 커서를 재사용하는 것을 막기 위해 검증용으로 쓴다.
 * @param lastCreatedAt     이전 페이지 마지막 항목의 생성 시각 — keyset 조건의 기준값.
 * @param lastId            이전 페이지 마지막 항목의 ID — created_at 동률일 때 tie-break 기준값.
 * @param filterFingerprint 이 커서가 발급될 때 적용된 필터 조건의 지문(해시 등). 커서 재사용 시
 *                          필터가 달라졌으면 거부하기 위한 값 — 구체적 계산 방식은 미정.
 * @param issuedAt          커서 발급 시각. 커서 만료 정책(TTL 등)을 도입할 때 사용.
 */
public record CursorPayload(
        int version,
        String sort,
        Instant lastCreatedAt,
        Long lastId,
        String filterFingerprint,
        Instant issuedAt
) {
}
