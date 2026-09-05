package com.safedeal.domain.trust.service;

import com.safedeal.domain.trust.dto.TrustScoreChange;
import com.safedeal.domain.trust.entity.TrustReasonCode;
import com.safedeal.domain.trust.entity.TrustRefType;
import com.safedeal.global.event.EventEnvelope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 신뢰도 점수를 움직이는 외부 이벤트 수신 (정책 '도메인 연계 계약').
 *
 * <b>계약은 클래스가 아니라 {@code eventType} + {@code eventVersion}이다</b>
 * ({@link EventEnvelope} 주석). payload는 Map으로 들어오고, 여기서 신뢰도 도메인의 명령으로
 * 옮긴 뒤 {@link TrustScoreService}에 넘긴다.
 *
 * <b>delta를 받지 않는다</b> — 정책이 "delta는 이벤트에 미탑재, 사유→점수 매핑표는 신뢰도
 * 도메인 소유"로 못 박았다. 발행 측은 "무슨 일이 있었는지"만 말한다.
 *
 * <b>group id</b>: {@code safedeal-trust-v1} 고정. 서버가 2대여도 한 대에서만 처리돼야
 * 점수가 두 번 반영되지 않는다(KafkaConfig의 도메인 side-effect 패턴).
 *
 * <b>실패 처리</b>: 예외를 그대로 올린다. 공통 에러 핸들러가 1초 간격 3회 재시도 후 DLT로
 * 보낸다. 점수는 재조회로 복구되지 않는 값이라 조용히 삼키면 영구 누락된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrustEventConsumer {

    private static final String GROUP = "safedeal-trust-v1";

    /** 거래완료 (TXN-7). 구매확정 시 order_item당 buyer 1건 + seller 1건이 온다. */
    private static final String TRADE_COMPLETED = "trade.completed";

    /** 제재 확정 (RPT-6). 신고가 RESOLVED로 종결될 때 온다. */
    private static final String REPORT_RESOLVED = "report.resolved";

    private static final int SUPPORTED_VERSION = 1;

    private final TrustScoreService trustScoreService;

    @KafkaListener(topics = "${app.kafka.topics.trade-events}", groupId = GROUP)
    public void onTradeEvent(EventEnvelope<Map<String, Object>> envelope) {
        if (!accepts(envelope, TRADE_COMPLETED)) {
            return;
        }
        trustScoreService.apply(new TrustScoreChange(
                requiredLong(envelope, "userId"),
                TrustReasonCode.TRADE_COMPLETED,
                TrustRefType.ORDER_ITEM,
                requiredLong(envelope, "refId"),
                requiredLong(envelope, "counterpartyId"),
                idempotencyKey(envelope)));
    }

    /**
     * 제재 확정은 사유(FRAUD·SPAM 등)와 무관하게 한 가지 감점으로 처리한다.
     *
     * 정책이 "어떤 제재든 받으면 −30"으로 확정했고, 신고 건수 누적에 따른 차등 감점은 v2다.
     * payload의 {@code reasonCode}는 신고 사유라 점수 매핑에 쓰지 않는다 — 쓰기 시작하면
     * 신고 도메인의 사유 목록이 바뀔 때마다 신뢰도가 따라 움직여야 한다.
     */
    @KafkaListener(topics = "${app.kafka.topics.report-events}", groupId = GROUP)
    public void onReportEvent(EventEnvelope<Map<String, Object>> envelope) {
        if (!accepts(envelope, REPORT_RESOLVED)) {
            return;
        }
        trustScoreService.apply(new TrustScoreChange(
                requiredLong(envelope, "userId"),
                TrustReasonCode.REPORT_CONFIRMED,
                TrustRefType.REPORT,
                requiredLong(envelope, "refId"),
                null,
                idempotencyKey(envelope)));
    }

    /**
     * 한 토픽에 여러 eventType이 흐를 수 있으므로 관심 있는 것만 고른다.
     *
     * 모르는 eventType은 조용히 넘긴다 — 남의 도메인이 새 이벤트를 추가했다고 우리 컨슈머가
     * DLT를 쌓으면 안 된다. 반대로 <b>아는 eventType인데 버전이 다르면</b> 터뜨린다. 그건
     * 계약이 바뀌었는데 우리가 못 따라간 상태라, 조용히 넘기면 점수가 통째로 누락된다.
     */
    private boolean accepts(EventEnvelope<Map<String, Object>> envelope, String eventType) {
        if (!eventType.equals(envelope.eventType())) {
            return false;
        }
        if (envelope.eventVersion() != SUPPORTED_VERSION) {
            throw new IllegalStateException("지원하지 않는 이벤트 버전입니다 - type=" + eventType
                    + " version=" + envelope.eventVersion() + " (지원: " + SUPPORTED_VERSION + ")");
        }
        return true;
    }

    /**
     * 멱등 키. payload의 {@code eventId}를 먼저 쓰고 없으면 봉투의 것을 쓴다.
     *
     * 정책의 payload 계약에 eventId가 들어 있고, RPT-6은 그것을 "대상 타입 + 대상 ID"로
     * 만들라고 한다 — 즉 재발행해도 같은 값이다. 봉투의 eventId는 발행 시마다 새로 만들어질
     * 수 있어(UUID) 재시도를 못 걸러낼 수 있으므로 payload 쪽을 우선한다.
     */
    private String idempotencyKey(EventEnvelope<Map<String, Object>> envelope) {
        Object fromPayload = payload(envelope).get("eventId");
        if (fromPayload != null && !fromPayload.toString().isBlank()) {
            return fromPayload.toString();
        }
        return envelope.eventId();
    }

    private Map<String, Object> payload(EventEnvelope<Map<String, Object>> envelope) {
        Map<String, Object> payload = envelope.payload();
        if (payload == null) {
            throw new IllegalArgumentException("payload가 비어 있습니다 - eventId=" + envelope.eventId());
        }
        return payload;
    }

    /**
     * JSON 숫자는 크기에 따라 Integer나 Long으로 들어오고, 문자열로 오는 발행 측도 있다.
     * 여기서 한 번에 흡수하지 않으면 발행 측 구현 차이가 그대로 ClassCastException이 된다.
     */
    private Long requiredLong(EventEnvelope<Map<String, Object>> envelope, String field) {
        Object value = payload(envelope).get(field);
        if (value == null) {
            throw new IllegalArgumentException(
                    "필수 필드가 없습니다 - field=" + field + " eventId=" + envelope.eventId());
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "숫자가 아닙니다 - field=" + field + " eventId=" + envelope.eventId(), e);
        }
    }
}
