package com.safedeal.global.config;

import com.safedeal.global.filter.RequestIdFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * {@link RequestIdFilter}를 서블릿 컨테이너 필터 체인에 등록한다.
 *
 * Spring Security의 필터 체인(springSecurityFilterChain)은 그 자체가 서블릿 컨테이너에
 * 등록되는 필터 "하나"이고, Spring Boot가 기본적으로 이를 상대적으로 이른 순서(음수 order)에
 * 등록한다. RequestIdFilter는 인증/인가와 무관하게 모든 요청(에러 응답 포함)에 requestId를
 * 부여해야 하므로 Security 필터보다도 앞서야 한다.
 *
 * SecurityConfig(다른 담당자 소유)의 SecurityFilterChain 빈에 addFilterBefore로 끼워 넣는
 * 방식 대신, 이렇게 별도의 FilterRegistrationBean으로 서블릿 컨테이너 레벨에 직접 등록하는
 * 방식을 택했다 — SecurityConfig 파일을 건드리지 않고도(소유권 충돌 없이) 항상 Security
 * 필터보다 먼저 실행되는 것을 order 값만으로 보장할 수 있기 때문이다.
 * order를 {@link Ordered#HIGHEST_PRECEDENCE}로 지정해 어떤 값으로 Security 필터 순서가
 * 바뀌더라도 항상 그보다 먼저 실행되게 한다.
 */
@Configuration
public class FilterConfig {

    @Bean
    public FilterRegistrationBean<RequestIdFilter> requestIdFilterRegistration() {
        FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>(new RequestIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
