package com.safedeal.global.config;

import com.safedeal.global.security.ApiAccessDeniedHandler;
import com.safedeal.global.security.ApiAuthenticationEntryPoint;
import com.safedeal.global.security.DevAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

// 모든 환경(local/prod 등)에서 동일한 보호 규칙을 적용한다. 프로파일에 따라 규칙 자체를
// 느슨하게 만들지 않는다 — 유일한 예외는 springdoc(swagger) 노출 여부이며, 그 이유는
// 아래 authorizeHttpRequests 안의 주석 참고.
@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final CorsProperties corsProperties;
    private final ApiAuthenticationEntryPoint apiAuthenticationEntryPoint;
    private final ApiAccessDeniedHandler apiAccessDeniedHandler;
    private final Environment environment;
    private final ObjectProvider<DevAuthenticationFilter> devAuthenticationFilterProvider;

    // 인증 없이 여는 경로. 도메인이 늘어나도 여기 한 곳만 보면 전체 화이트리스트를 알 수 있다.

    // 회원가입/로그인/토큰 재발급 - 아직 컨트롤러는 없지만 경로를 미리 예약해둔다.
    private static final String[] AUTH_PUBLIC_POST_ENDPOINTS = {
            "/api/v1/auth/signup",
            "/api/v1/auth/login",
            "/api/v1/auth/reissue",
    };

    // 매물 목록 공개 조회. 쓰기 메서드(POST/PATCH/DELETE)는 이 매처에 포함하지 않는다.
    // 하위 경로를 /** 로 열지 않는 이유: 나중에 /mine, /drafts, /{id}/buyers 처럼 비공개여야
    // 할 조회가 같은 prefix 아래 추가되면 검토 없이 자동 공개되기 때문이다. 상세 조회 등
    // 공개가 필요한 경로는 컨트롤러를 추가할 때 정확한 패턴으로 여기에 함께 등록한다.
    private static final String[] LISTINGS_PUBLIC_GET_ENDPOINTS = {
            "/api/v1/listings",
    };

    // 헬스체크만 공개. prometheus/info는 운영 지표 노출이라 인증 뒤로 둔다.
    // (하위 경로까지 열지 않도록 정확히 /actuator/health 만 지정)
    private static final String[] ACTUATOR_PUBLIC_GET_ENDPOINTS = {
            "/actuator/health",
    };

    // springdoc(swagger-ui)는 local 프로파일에서만 화이트리스트에 추가한다(아래 참고).
    // 운영에서는 springdoc 자동설정 자체를 yml에서 끄는 방식으로 막을 예정이라 여기서는
    // 프로파일 조건부 permitAll만 담당한다.
    private static final String[] SPRINGDOC_ENDPOINTS = {
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/v3/api-docs.yaml",
            "/swagger-ui.html",
            "/swagger-ui/**",
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(apiAuthenticationEntryPoint)
                        .accessDeniedHandler(apiAccessDeniedHandler))
                .authorizeHttpRequests(auth -> {
                    auth.dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll();

                    auth.requestMatchers(HttpMethod.POST, AUTH_PUBLIC_POST_ENDPOINTS).permitAll();
                    auth.requestMatchers(HttpMethod.GET, LISTINGS_PUBLIC_GET_ENDPOINTS).permitAll();
                    auth.requestMatchers(HttpMethod.GET, ACTUATOR_PUBLIC_GET_ENDPOINTS).permitAll();

                    // WebSocket(/ws/**) 화이트리스트는 의도적으로 넣지 않았다. 아직 endpoint도
                    // STOMP CONNECT 인증도 없는 상태에서 경로를 미리 열어두면, 나중에 그 아래
                    // 기능이 추가되는 순간 자동으로 공개된다. 채팅 구현 시 핸드셰이크 GET 경로만
                    // 정확히 열고, CONNECT 프레임 JWT 검증·destination 인가·origin 제한을
                    // 같은 변경에서 함께 추가한다.

                    // springdoc 자체가 설정으로 꺼져 있지 않은 한(local) 화이트리스트에 추가한다.
                    // 별도 @Profile SecurityFilterChain 빈으로 쪼개는 대신 Environment로
                    // 조건부 화이트리스트를 적용한 이유: 규칙 하나 때문에 STATELESS/CORS/예외
                    // 처리 같은 나머지 보안 설정 전체를 프로파일별로 중복 정의하고 싶지 않았음.
                    if (environment.matchesProfiles("local")) {
                        auth.requestMatchers(SPRINGDOC_ENDPOINTS).permitAll();
                    }

                    auth.anyRequest().authenticated();
                });

        // 로컬 전용 임시 인증 필터. local + app.security.dev-auth-enabled=true 일 때만 빈이
        // 존재하므로(DevAuthenticationFilter 참고) 그 외에는 아래 if를 타지 않는다.
        DevAuthenticationFilter devAuthenticationFilter = devAuthenticationFilterProvider.getIfAvailable();
        if (devAuthenticationFilter != null) {
            http.addFilterBefore(devAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        }

        // 실제 JWT 인증 필터는 인증 담당자가 여기에 추가한다:
        // http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(corsProperties.getAllowedOrigins());
        configuration.setAllowedMethods(corsProperties.getAllowedMethods());
        configuration.setAllowedHeaders(corsProperties.getAllowedHeaders());
        configuration.setExposedHeaders(corsProperties.getExposedHeaders());
        configuration.setAllowCredentials(corsProperties.isAllowCredentials());
        configuration.setMaxAge(corsProperties.getMaxAge());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
