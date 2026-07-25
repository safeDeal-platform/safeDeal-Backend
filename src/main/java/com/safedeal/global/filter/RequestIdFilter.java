package com.safedeal.global.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 모든 요청에 요청 추적용 ID를 부여한다.
 *
 * 인바운드 {@code X-Request-Id} 헤더가 있고 형식이 유효하면 그대로 사용하고(클라이언트/게이트웨이가
 * 발급한 추적 ID를 이어받기 위함), 없거나 형식이 잘못됐으면 새 UUID를 발급한다. 클라이언트가
 * 보낸 값을 검증 없이 그대로 로그에 남기면 로그 인젝션/과도하게 긴 값으로 인한 문제가 생길 수
 * 있어 길이와 허용 문자를 제한한다.
 *
 * MDC 키는 {@value #MDC_KEY}로 고정한다 — 로그 패턴(logback-spring.xml)에서 이 키로 참조한다.
 * 스레드 풀이 재사용되므로 finally에서 반드시 MDC를 제거해야 다음 요청에 이전 요청의 ID가
 * 새어 들어가는 것을 막을 수 있다.
 *
 * 응답 헤더에도 {@code X-Request-Id}를 그대로 내려줘서 클라이언트가 문의/로그 상관관계에
 * 쓸 수 있게 한다.
 */
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    private static final int MAX_LENGTH = 64;
    private static final Pattern ALLOWED_CHARS = Pattern.compile("^[a-zA-Z0-9-]+$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = resolveRequestId(request.getHeader(HEADER_NAME));
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER_NAME, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private String resolveRequestId(String inbound) {
        if (inbound != null && !inbound.isBlank()
                && inbound.length() <= MAX_LENGTH
                && ALLOWED_CHARS.matcher(inbound).matches()) {
            return inbound;
        }
        return UUID.randomUUID().toString();
    }
}
