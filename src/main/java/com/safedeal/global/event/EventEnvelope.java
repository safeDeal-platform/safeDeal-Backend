package com.safedeal.global.event;

import java.time.Instant;

/**
 * Kafka로 발행되는 모든 도메인 이벤트가 공통으로 따르는 봉투(envelope).
 *
 * 계약의 기준은 Java 클래스명이 아니라 {@code eventType} + {@code eventVersion}이다.
 * 프로듀서/컨슈머가 다른 배포 버전(다른 JVM, 다른 클래스 구조)일 수 있고, 리팩터링으로
 * 클래스명이 바뀔 수도 있기 때문에 Kafka 메시지 타입 헤더(예: Spring Kafka의
 * {@code __TypeId__})를 자바 클래스로 채워서 계약처럼 쓰지 않는다. 컨슈머는 반드시
 * {@code eventType} 문자열과 {@code eventVersion} 정수로 페이로드를 해석해야 하며,
 * 이 두 값이 이벤트의 실제 계약(schema)이다. eventVersion을 올릴 때는 하위 호환을
 * 깨지 않는 선에서 필드를 추가하거나, 깨는 변경이면 eventType 자체를 바꾼다.
 *
 * @param eventId        이벤트 고유 ID (UUID). 컨슈머 측 중복 처리 방지(idempotency) 키로 사용.
 * @param eventType       이벤트 계약 이름 (예: "order.created"). Java 클래스명과 무관하게 고정.
 * @param eventVersion    eventType의 스키마 버전.
 * @param aggregateType   이벤트가 속한 애그리거트 타입 (예: "order").
 * @param aggregateId     애그리거트 식별자 (예: 주문 ID).
 * @param occurredAt      이벤트가 실제로 발생한 시각 (Kafka 적재 시각이 아님).
 * @param producer        이벤트를 발행한 서비스/모듈 이름.
 * @param correlationId   요청 추적용 상관관계 ID. {@code X-Request-Id}를 그대로 이어받는 데 사용.
 * @param payload         실제 이벤트 데이터.
 */
public record EventEnvelope<T>(
        String eventId,
        String eventType,
        int eventVersion,
        String aggregateType,
        String aggregateId,
        Instant occurredAt,
        String producer,
        String correlationId,
        T payload
) {
}
