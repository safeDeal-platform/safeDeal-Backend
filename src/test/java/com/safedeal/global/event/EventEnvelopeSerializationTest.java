package com.safedeal.global.event;

import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kafka 이벤트 봉투의 직렬화 왕복을 고정한다.
 *
 * application.yml의 producer/consumer 설정과 동일한 옵션으로 JsonSerializer /
 * JsonDeserializer를 구성해서 검증한다 — 여기서 깨지면 도메인 이벤트 발행 전체가 깨진다.
 * (설정 출처: spring.kafka.producer.*, spring.kafka.consumer.* — 값을 바꾸면 이 테스트도
 * 같이 바꿔야 한다. 그게 이 테스트의 목적이다.)
 */
class EventEnvelopeSerializationTest {

    private static final String TOPIC = "safedeal.test.v1";

    @SuppressWarnings({"unchecked", "rawtypes"})
    private byte[] serialize(EventEnvelope<?> envelope) {
        try (JsonSerializer serializer = new JsonSerializer<>()) {
            // application.yml: spring.json.add.type.headers=false
            serializer.configure(Map.of(JsonSerializer.ADD_TYPE_INFO_HEADERS, false), false);
            return serializer.serialize(TOPIC, envelope);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private EventEnvelope<Map<String, Object>> deserialize(byte[] bytes) {
        try (JsonDeserializer deserializer = new JsonDeserializer<>()) {
            // application.yml: use.type.headers=false + value.default.type + trusted.packages
            deserializer.configure(Map.of(
                    JsonDeserializer.USE_TYPE_INFO_HEADERS, false,
                    JsonDeserializer.VALUE_DEFAULT_TYPE, EventEnvelope.class.getName(),
                    JsonDeserializer.TRUSTED_PACKAGES, "com.safedeal.global.event,com.safedeal.**.event"
            ), false);
            return (EventEnvelope<Map<String, Object>>) deserializer.deserialize(TOPIC, new RecordHeaders(), bytes);
        }
    }

    @Test
    @DisplayName("EventEnvelope는 타입 헤더 없이 JSON으로 왕복한다")
    void roundTrip() {
        EventEnvelope<Map<String, Object>> original = new EventEnvelope<>(
                "evt-001", "report.resolved", 1, "report", "77",
                Instant.parse("2026-08-02T00:00:00Z"), "safedeal-backend", "corr-1",
                Map.of("targetType", "LISTING", "note", "한글 페이로드 확인"));

        EventEnvelope<Map<String, Object>> restored = deserialize(serialize(original));

        assertThat(restored.eventId()).isEqualTo("evt-001");
        assertThat(restored.eventType()).isEqualTo("report.resolved");
        assertThat(restored.eventVersion()).isEqualTo(1);
        assertThat(restored.aggregateId()).isEqualTo("77");
        assertThat(restored.occurredAt()).isEqualTo(Instant.parse("2026-08-02T00:00:00Z"));
        assertThat(restored.payload())
                .containsEntry("targetType", "LISTING")
                .containsEntry("note", "한글 페이로드 확인");
    }

    @Test
    @DisplayName("직렬화 결과에 자바 클래스명이 들어가지 않는다 — 계약은 eventType이다")
    void serializedForm_hasNoJavaClassName() {
        byte[] bytes = serialize(new EventEnvelope<>(
                "evt-002", "probe.created", 1, "probe", "1",
                Instant.parse("2026-08-02T00:00:00Z"), "test", null, Map.of()));

        assertThat(new String(bytes)).doesNotContain("com.safedeal");
    }
}
