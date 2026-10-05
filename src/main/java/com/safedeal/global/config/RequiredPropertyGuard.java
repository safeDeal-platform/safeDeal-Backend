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
 * 필수 환경변수가 빠져도 Spring은 예외 없이 {@code "${REDIS_HOST}"}라는 문자열 그대로 값을
 * 채워 넣는다 — 앱은 정상 기동한 뒤 나중에 그 이름의 호스트에 접속하려다 실패해서 원인
 * 추적이 오래 걸린다. 그래서 기동 시점에 값이 비었거나 미해석 플레이스홀더인지 직접 확인하고,
 * 하나라도 걸리면 전부 모아서 한 번에 실패시킨다.
 */
@Component
@Profile("prod")
@RequiredArgsConstructor
public class RequiredPropertyGuard {

    // 해석되지 않은 플레이스홀더는 "${...}" 형태 그대로 남는다.
    private static final Pattern UNRESOLVED = Pattern.compile("^\\$\\{.*}$");

    private static final String MAIL_PROVIDER_KEY = "app.mail.provider";
    private static final String LOG_MAIL_PROVIDER = "log";

    private static final List<String> REQUIRED_PROPERTIES = List.of(
            "spring.datasource.url",
            "spring.datasource.username",
            "spring.datasource.password",
            "spring.data.redis.host",
            "spring.kafka.bootstrap-servers",
            "app.cors.allowed-origins",
            // 서버마다 달라야 하는 값. 같은 값이면 서버 2대가 한 consumer group에 묶여
            // 채팅 fan-out이 한 대에서만 처리된다.
            "app.kafka.consumer-group.chat-fanout",
            // 빠뜨리면 토큰 서명 키가 없어 인증이 통째로 깨진다. 게다가 공용 설정에 기본값을
            // 두지 않았으므로(의도) 여기서 못 잡으면 첫 로그인 요청에서야 드러난다.
            "app.jwt.secret",
            // 값이 log인 경우는 아래 checkRequiredProperties에서 따로 막는다 — 있기만 해서는 부족하다.
            MAIL_PROVIDER_KEY
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

        // MAIL_PROVIDER=log면 필수값 검사는 통과하지만 LoggingMailSender가 붙어 메일이 안
        // 나가고 수신자 주소·인증 링크가 운영 로그에 그대로 쌓인다 — 그래서 여기서 따로 막는다.
        if (LOG_MAIL_PROVIDER.equalsIgnoreCase(environment.getProperty(MAIL_PROVIDER_KEY))) {
            throw new IllegalStateException(
                    "운영에서는 " + MAIL_PROVIDER_KEY + "=" + LOG_MAIL_PROVIDER + "를 쓸 수 없습니다"
                            + " — 메일이 발송되지 않고 수신자 주소가 로그에 남습니다."
                            + " MAIL_PROVIDER를 resend로 설정하세요.");
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
