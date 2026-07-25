package com.safedeal.global.config;

import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.List;

// 값은 코드에 기본값을 두지 않고 app.cors.* yml 설정에서만 받는다 (환경별로 달라야 하는 값이라
// 코드에 기본값을 박아두면 운영에서 실수로 개발용 오리진이 열려 있을 위험이 있다).
@Component
@Validated
@Getter
@Setter
@ConfigurationProperties(prefix = "app.cors")
public class CorsProperties {

    @NotEmpty
    private List<String> allowedOrigins;

    @NotEmpty
    private List<String> allowedMethods;

    @NotEmpty
    private List<String> allowedHeaders;

    private List<String> exposedHeaders;

    private boolean allowCredentials;

    private long maxAge;
}
