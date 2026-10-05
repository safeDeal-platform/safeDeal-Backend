package com.safedeal.global.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code app.kafka.*} 설정 바인딩 자리. 아직 토픽이 확정되지 않아 필드를 미리 만들지 않는다 —
 * 실제 토픽이 정해지면 이 클래스에 필드를 추가하거나 {@link #topics} 맵을 쓴다. consumer group
 * 네이밍 규칙은 {@link KafkaConfig} 참고.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.kafka")
public class KafkaTopicProperties {

    private Map<String, String> topics = new HashMap<>();
}
