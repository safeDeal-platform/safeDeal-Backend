package com.safedeal.global.response;

import java.util.List;

/**
 * 커서 기반(keyset) 페이징 응답 — 총 개수가 필요 없고 계속 늘어나는 목록에 쓴다. 오프셋
 * 방식과 달리 조회 중간에 앞쪽에 새 데이터가 추가돼도 항목이 밀리거나 중복되지 않는다.
 *
 * {@code totalElements}는 뺐다 — 필요하면 COUNT 쿼리가 붙는 {@link PageResponse}를 쓴다.
 *
 * keyset 조회 규칙(쿼리는 도메인 담당자가 구현): {@code ORDER BY created_at DESC, id DESC}로
 * 정렬하고 {@code size + 1}건을 조회해 초과분이 있으면 {@code hasNext = true}로 잘라낸다.
 * 다음 페이지 조건: {@code created_at < :c OR (created_at = :c AND id < :id)}
 * (:c, :id는 {@link CursorPayload}의 lastCreatedAt/lastId).
 *
 * @param nextCursor 다음 페이지 조회에 쓸 커서. 마지막 페이지면 {@code null}.
 */
public record CursorResponse<T>(
        List<T> items,
        String nextCursor,
        boolean hasNext
) {
}
