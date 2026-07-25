package com.safedeal.global.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code app.kafka.*} 설정 바인딩 자리.
 *
 * 어떤 도메인 이벤트/토픽이 필요한지 아직 확정되지 않았으므로 임의의 필드를 미리 만들지 않는다.
 * 도메인 담당자가 실제 토픽이 결정되면 이 클래스에 필드를 추가한다. 예:
 * <pre>
 *   private String orderEvents; // application.yml의 app.kafka.order-events
 * </pre>
 * 또는 아래 {@link #topics} 맵에 {@code app.kafka.topics.order-events: safedeal.order.events.v1}
 * 형태로 추가해도 된다 — 개별 필드 vs 맵 중 어느 쪽을 쓸지는 토픽 수가 늘어난 뒤 담당자가 정한다.
 *
 * consumer group 네이밍 규칙(고정 group vs 서버별 group)은 {@link KafkaConfig} 클래스 주석 참고.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.kafka")
public class KafkaTopicProperties {

    private Map<String, String> topics = new HashMap<>();
}
