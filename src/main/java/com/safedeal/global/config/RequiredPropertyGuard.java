package com.safedeal.global.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 운영 기동에 반드시 필요한 값이 실제로 주입됐는지 확인한다.
 *
 * application-prod.yml에서 기본값을 지워 {@code ${REDIS_HOST}}처럼 남겨두는 것만으로는
 * 부족하다. Spring이 설정 속성을 바인딩할 때 해석하지 못한 플레이스홀더는 예외가 아니라
 * <b>문자열 그대로</b> 들어간다. 실제로 REDIS_HOST를 빼고 prod로 기동해보면 앱은 정상적으로
 * 뜨고, 나중에 "${REDIS_HOST}"라는 이름의 호스트에 접속하려다 실패한다. 원인은 배포 설정인데
 * 증상은 런타임 연결 장애로 나타나 추적이 오래 걸린다.
 * (int인 REDIS_PORT만 타입 변환 실패로 우연히 걸렸다 — 타입에 기대는 건 방어가 아니다.)
 *
 * 그래서 기동 시점에 값이 비어 있거나 해석되지 않은 플레이스홀더인지 직접 확인하고,
 * 하나라도 걸리면 전부 모아서 실패시킨다. 하나씩 고쳐가며 재배포하는 상황을 피하기 위함이다.
 */
@Component
@Profile("prod")
@RequiredArgsConstructor
public class RequiredPropertyGuard {

    // 해석되지 않은 플레이스홀더는 "${...}" 형태 그대로 남는다.
    private static final Pattern UNRESOLVED = Pattern.compile("^\\$\\{.*}$");

    private static final List<String> REQUIRED_PROPERTIES = List.of(
            "spring.datasource.url",
            "spring.datasource.username",
            "spring.datasource.password",
            "spring.data.redis.host",
            "spring.kafka.bootstrap-servers",
            "app.cors.allowed-origins",
            // 서버마다 달라야 하는 값. 같은 값이면 서버 2대가 한 consumer group에 묶여
            // 채팅 fan-out이 한 대에서만 처리된다.
            "app.kafka.consumer-group.chat-fanout"
    );

    private final Environment environment;

    @PostConstruct
    public void checkRequiredProperties() {
        List<String> missing = REQUIRED_PROPERTIES.stream()
                .filter(this::isMissing)
                .toList();

        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "운영 기동에 필요한 설정이 비어 있거나 환경변수가 주입되지 않았습니다: " + missing
                            + " — 배포 환경변수(REDIS_HOST, KAFKA_BOOTSTRAP_SERVERS, CORS_ALLOWED_ORIGINS,"
                            + " INSTANCE_ID 등)를 확인하세요.");
        }
    }

    private boolean isMissing(String key) {
        String value;
        try {
            value = environment.getProperty(key);
        } catch (IllegalArgumentException e) {
            // getProperty는 값 안의 플레이스홀더를 해석하며, 해석 못 하면 예외를 던진다.
            // 여기서 그대로 터뜨리면 첫 번째 항목에서 멈춰 나머지 누락을 못 모으므로 삼킨다.
            return true;
        }
        return value == null || value.isBlank() || UNRESOLVED.matcher(value.trim()).matches();
    }
}
