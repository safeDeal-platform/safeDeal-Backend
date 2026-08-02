package com.safedeal.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka cross-cutting 설정.
 *
 * {@code ProducerFactory}/{@code ConsumerFactory}/{@code KafkaTemplate}/기본
 * {@code ListenerContainerFactory}는 여기서 재정의하지 않는다 — Boot 자동설정 +
 * application.yml의 {@code spring.kafka.*} 값을 그대로 쓴다. 이 클래스는 모든 리스너에
 * 공통으로 적용할 에러 처리 정책만 다룬다.
 *
 * ── 직렬화 계약 ──
 * 값은 JSON으로 주고받고 자바 클래스명 타입 헤더는 싣지 않는다(application.yml 참고).
 * 컨슈머는 {@link com.safedeal.global.event.EventEnvelope}로 역직렬화되며, 이때 payload는
 * 특정 도메인 타입이 아니라 Map 형태로 들어온다. 도메인 담당자는 {@code eventType}과
 * {@code eventVersion}을 먼저 확인한 뒤 payload를 자기 도메인 타입으로 변환한다 —
 * 클래스명에 의존하지 않는 것이 이 계약의 핵심이다.
 *
 * ── consumer group 네이밍 규칙 ──
 * 별도 ConsumerFactory를 만들지 않아도 {@code @KafkaListener(groupId = "...")}로 그룹을
 * 충분히 나눌 수 있다. 아래 두 패턴만 지킨다.
 *
 * 1) 도메인 side-effect 처리용 (예: 주문 생성 후 알림 발송): 고정 group id를 쓴다.
 *    group id: {@code safedeal-<도메인>-v1} (예: safedeal-order-v1, safedeal-notification-v1)
 *    서버가 2대 떠 있어도 같은 group이면 파티션을 나눠 가지므로 메시지는 둘 중 한 대에서만
 *    처리된다(중복 side effect 방지). 도메인 담당자가 리스너에 직접 문자열로 지정하면 된다.
 *
 * 2) 채팅 WebSocket fan-out용: 서버별로 다른 group id를 써야 모든 서버가 동일 메시지를
 *    각자 수신해서 자신에게 연결된 세션에만 전달할 수 있다.
 *    group id: {@code safedeal-chat-fanout-${INSTANCE_ID}}
 *    (application.yml의 {@code app.kafka.consumer-group.chat-fanout} 참고, 리스너에서는
 *    {@code @KafkaListener(groupId = "${app.kafka.consumer-group.chat-fanout}")}로 참조)
 *    {@code INSTANCE_ID}는 배포 환경(ECS task ID 등)이 주입하는 서버별 고유값이다. 앱이
 *    기동할 때마다 임의로 UUID를 생성해서 쓰지 않는다 — 그렇게 하면 재배포/재시작마다 새
 *    consumer group이 생겨 Kafka에 그룹이 무한정 쌓이고, 오프셋 추적도 매번 새로 시작된다.
 *
 * ── 에러 처리 정책 ──
 * 모든 리스너 컨테이너에 공통으로 적용되는 {@link CommonErrorHandler}를 하나 등록한다.
 * 고정 백오프(1초 간격, 최대 3회 재시도) 후에도 실패하면 Dead Letter Topic으로 보낸다.
 * 재시도/백오프 값을 조정하려면 {@link #DLT_RETRY_INTERVAL_MS}, {@link #DLT_MAX_RETRIES}만
 * 바꾸면 된다 — 리스너별로 다른 정책이 필요해지면 그때 개별 ErrorHandler를 리스너에
 * 지정하는 것으로 확장한다(지금은 공통 정책 하나로 충분).
 */
@Configuration
public class KafkaConfig {

    // 조정 지점: 재시도 간격(ms)과 최대 재시도 횟수. DLT로 넘어가기 전 총 대기 시간은
    // DLT_RETRY_INTERVAL_MS * DLT_MAX_RETRIES.
    private static final long DLT_RETRY_INTERVAL_MS = 1000L;
    private static final long DLT_MAX_RETRIES = 3L;

    // Boot 자동설정이 등록하는 KafkaTemplate 빈의 타입은 KafkaTemplate<?, ?>이다.
    // KafkaTemplate<Object, Object>로 받으면 제네릭이 맞지 않아 주입에 실패하므로
    // 와일드카드 타입으로 받는다.
    @Bean
    public CommonErrorHandler kafkaCommonErrorHandler(KafkaTemplate<?, ?> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);
        FixedBackOff backOff = new FixedBackOff(DLT_RETRY_INTERVAL_MS, DLT_MAX_RETRIES);
        return new DefaultErrorHandler(recoverer, backOff);
    }
}
