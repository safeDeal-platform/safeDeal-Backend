package com.safedeal.global.security;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// DevAuthenticationFilter는 @Profile("local") 조건으로 prod에서는 빈 자체가 생기지 않지만,
// 설정 실수(예: prod에서 app.security.dev-auth-enabled=true를 넣는 경우)를 대비한 이중 안전장치.
// prod 프로파일에서 이 값이 true면 애플리케이션 기동을 즉시 실패시킨다.
@Component
@Profile("prod")
public class DevAuthPropertyGuard {

    @Value("${app.security.dev-auth-enabled:false}")
    private boolean devAuthEnabled;

    @PostConstruct
    public void checkDevAuthDisabled() {
        if (devAuthEnabled) {
            throw new IllegalStateException(
                    "prod 프로파일에서는 app.security.dev-auth-enabled=true를 사용할 수 없습니다.");
        }
    }
}
