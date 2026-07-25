package com.safedeal.global.response;

import java.util.List;

/**
 * 커서 기반(keyset) 페이징 응답 계약. 무한 스크롤처럼 총 개수가 필요 없고 데이터가
 * 계속 늘어나는 목록에 쓴다 — 오프셋 방식과 달리 뒤 페이지로 갈수록 느려지지 않고,
 * 조회 중간에 앞쪽에 새 데이터가 추가돼도 항목이 밀리거나 중복되지 않는다.
 *
 * {@code totalElements}는 의도적으로 두지 않는다 — 커서 방식에서 전체 개수를 구하려면
 * 별도의 비싼 COUNT 쿼리가 필요하고, 그럴 거면 오프셋 페이징({@link PageResponse})을
 * 쓰는 게 맞다. 총 개수가 필요한 화면은 커서 방식을 쓰지 않는다.
 *
 * keyset 조회 규칙 (실제 쿼리는 도메인 담당자가 구현):
 *  - 정렬: {@code ORDER BY created_at DESC, id DESC} (동시에 생성된 레코드의 tie-break를 id로 고정)
 *  - {@code size + 1}건을 조회해서, size를 초과하는 마지막 1건이 있으면 {@code hasNext = true}로
 *    판정하고 그 초과분은 응답에서 잘라낸다.
 *  - 다음 페이지 조건: {@code created_at < :c OR (created_at = :c AND id < :id)}
 *    (:c, :id는 이전 페이지 마지막 항목의 created_at/id — {@link CursorPayload} 참고)
 *
 * @param nextCursor 다음 페이지 조회에 쓸 커서. 마지막 페이지면 {@code null}.
 */
public record CursorResponse<T>(
        List<T> items,
        String nextCursor,
        boolean hasNext
) {
}
