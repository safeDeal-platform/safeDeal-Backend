package com.safedeal.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka cross-cutting 설정. Producer/Consumer/Template은 Boot 자동설정 값을 그대로 쓰고,
 * 이 클래스는 모든 리스너에 공통으로 적용할 에러 처리 정책만 다룬다 — 고정 백오프 재시도 후에도
 * 실패하면 Dead Letter Topic으로 보낸다.
 *
 * consumer group은 두 규칙만 지킨다: 도메인 side-effect 처리(예: 주문 생성 후 알림)는 고정
 * group id {@code safedeal-<도메인>-v1}를 써서 서버가 여러 대여도 한 대에서만 처리되게 하고
 * (중복 방지), 채팅 WebSocket fan-out처럼 서버마다 다 받아야 하면 배포 환경이 주는 고정
 * {@code INSTANCE_ID}로 서버별 group id를 쓴다 — 기동할 때마다 임의 UUID를 쓰면 재배포마다
 * 새 group이 쌓이고 오프셋 추적도 매번 새로 시작된다.
 */
@Configuration
public class KafkaConfig {

    // DLT로 넘어가기 전 총 대기 시간 = 간격 × 재시도 횟수.
    private static final long DLT_RETRY_INTERVAL_MS = 1000L;
    private static final long DLT_MAX_RETRIES = 3L;

    // Boot가 등록하는 빈은 KafkaTemplate<?, ?>라서 <Object, Object>로 받으면 주입이 실패한다.
    @Bean
    public CommonErrorHandler kafkaCommonErrorHandler(KafkaTemplate<?, ?> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);
        FixedBackOff backOff = new FixedBackOff(DLT_RETRY_INTERVAL_MS, DLT_MAX_RETRIES);
        return new DefaultErrorHandler(recoverer, backOff);
    }
}
