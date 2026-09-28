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
 * 신뢰도 점수를 움직이는 외부 이벤트 수신 (정책 '도메인 연계 계약'). 계약은 클래스가 아니라
 * {@code eventType}+{@code eventVersion}이다({@link EventEnvelope}). delta는 받지 않는다 —
 * "무슨 일이 있었는지"만 받고 "얼마인지"는 {@link TrustScoreService}가 정한다.
 *
 * group id는 {@code safedeal-trust-v1} 고정 — 서버가 여러 대여도 한 대에서만 처리돼야
 * 점수가 두 번 반영되지 않는다. 실패하면 예외를 그대로 올린다 — 공통 에러 핸들러가 재시도
 * 후 DLT로 보내는데, 점수는 재조회로 복구되지 않는 값이라 조용히 삼키면 영구 누락된다.
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
     * 제재 확정은 사유와 무관하게 한 가지 감점으로 처리한다(정책 확정, 차등 감점은 v2).
     * payload의 {@code reasonCode}는 신고 사유일 뿐 점수 매핑에 쓰지 않는다 — 쓰면 신고
     * 도메인의 사유 목록이 바뀔 때마다 신뢰도가 따라 움직여야 한다.
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
     * 한 토픽에 여러 eventType이 흐르므로 관심 있는 것만 고른다. 모르는 eventType은 조용히
     * 넘기지만, <b>아는 eventType인데 버전이 다르면</b> 터뜨린다 — 계약이 바뀐 건데 조용히
     * 넘기면 점수가 통째로 누락된다.
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
     * 멱등 키. payload의 {@code eventId}를 먼저 쓴다 — RPT-6은 이를 "대상 타입 + 대상 ID"로
     * 만들어 재발행해도 같은 값이지만, 봉투의 eventId는 매번 새로 생성돼(UUID) 재시도를
     * 못 걸러낼 수 있다.
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
