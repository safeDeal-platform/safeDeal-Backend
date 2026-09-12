package com.safedeal.domain.trust.dto;

import com.safedeal.domain.trust.entity.TrustReasonCode;
import com.safedeal.domain.trust.entity.TrustRefType;

/**
 * 점수 반영 요청 (도메인 내부 명령).
 *
 * 발행 측 이벤트 payload와 모양이 비슷하지만 일부러 분리했다. 이벤트는 Kafka 계약이라 남이
 * 바꾸면 따라 움직여야 하고, 이 record는 신뢰도 도메인이 소유하는 입력이다. 후기(REV)는
 * Kafka를 거치지 않고 같은 프로세스에서 이 명령을 그대로 만들어 넣는다 — 두 경로가 같은
 * 엔진을 타야 멱등·하한·어뷰징 규칙이 한 곳에만 있게 된다.
 *
 * <b>delta가 없는 것이 핵심이다</b> — 얼마인지는 {@link TrustReasonCode}가 정한다(정책 TRS-2).
 *
 * @param counterpartyId 거래 상대. 어뷰징 판정(정책 TRS-5)에 쓰이며 거래완료가 아니면 null이다.
 * @param eventId        멱등 키. 같은 사실을 다시 넣어도 한 번만 반영된다.
 */
public record TrustScoreChange(
        Long userId,
        TrustReasonCode reasonCode,
        TrustRefType refType,
        Long refId,
        Long counterpartyId,
        String eventId
) {
}
