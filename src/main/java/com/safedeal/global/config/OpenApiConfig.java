package com.safedeal.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc 기본 설정. 서버 URL은 하드코딩하지 않는다 — springdoc이 실제 요청 컨텍스트를
 * 기준으로 서버 URL을 자동 유추하므로, 환경마다(local/local docker/prod) 별도 설정 없이
 * 그대로 동작한다.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_AUTH_SCHEME_NAME = "bearerAuth";

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("SafeDeal API")
                        .description("SafeDeal 백엔드 API 명세")
                        .version("v1"))
                .components(new Components()
                        .addSecuritySchemes(BEARER_AUTH_SCHEME_NAME,
                                new SecurityScheme()
                                        .name(BEARER_AUTH_SCHEME_NAME)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")));
    }
}
