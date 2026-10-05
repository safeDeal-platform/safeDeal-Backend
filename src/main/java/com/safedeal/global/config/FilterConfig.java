package com.safedeal.global.config;

import com.safedeal.global.filter.RequestIdFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * {@link RequestIdFilter}를 서블릿 컨테이너 필터 체인에 등록한다.
 *
 * 인증 여부와 무관하게 모든 요청에 requestId가 있어야 해서 Security 필터보다 먼저 실행돼야
 * 한다. SecurityConfig(다른 담당자 소유)를 건드리지 않고도 항상 먼저 실행되도록,
 * {@link Ordered#HIGHEST_PRECEDENCE}로 서블릿 컨테이너 레벨에 직접 등록한다.
 */
@Configuration
public class FilterConfig {

    @Bean
    public FilterRegistrationBean<RequestIdFilter> requestIdFilterRegistration() {
        FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>(new RequestIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        // 기본 dispatcher(REQUEST)만 등록하면 에러 재디스패치(ERROR)와 비동기 응답(ASYNC)
        // 구간에서 이 필터가 안 타 로그에 requestId가 비게 된다.
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ERROR, DispatcherType.ASYNC);
        return registration;
    }
}
