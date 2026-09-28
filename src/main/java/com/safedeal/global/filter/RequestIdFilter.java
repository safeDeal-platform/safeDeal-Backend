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
 * 인바운드 {@code X-Request-Id}가 있고 형식이 유효하면 그대로 쓰고, 없거나 이상하면 새로
 * 발급한다. 클라이언트 값을 검증 없이 로그에 남기면 로그 인젝션이나 과도하게 긴 값 문제가
 * 생겨 길이와 허용 문자를 제한한다.
 *
 * MDC 키는 {@value #MDC_KEY}로 고정한다(logback-spring.xml이 이 키를 참조). 스레드 풀이
 * 재사용되므로 finally에서 반드시 지워야 다음 요청에 이전 ID가 새어 들어가지 않는다.
 */
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    private static final String REQUEST_ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";
    private static final int MAX_LENGTH = 64;
    private static final Pattern ALLOWED_CHARS = Pattern.compile("^[a-zA-Z0-9-]+$");

    /**
     * ERROR 재디스패치에서도 이 필터를 실행시킨다. 기본값(true)을 따르면 최초 요청이 끝나며
     * MDC를 지운 뒤라, 정작 장애 분석에 필요한 에러 페이지 로그에 requestId가 비어버린다.
     */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    /**
     * 비동기 재디스패치에서도 실행한다. 다만 MDC는 스레드 로컬이라 {@code Callable}·
     * {@code DeferredResult}를 실제로 처리하는 작업 스레드에는 전파되지 않는다 — 그쪽까지
     * 채우려면 별도 태스크 데코레이터가 필요하다(SSE·비동기 엔드포인트를 만들 때 할 일).
     * 여기서 살리는 건 결과를 응답으로 되돌리는 구간의 로그뿐이다.
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = resolveRequestId(request);
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER_NAME, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * ERROR 재디스패치는 같은 요청의 연장이라 최초에 정한 ID를 그대로 써야 한다 — 새로
     * 뽑으면 하나의 요청이 두 ID로 쪼개져 추적이 끊긴다. 그래서 request attribute에 남겨 되찾는다.
     */
    private String resolveRequestId(HttpServletRequest request) {
        Object cached = request.getAttribute(REQUEST_ATTRIBUTE);
        if (cached instanceof String cachedId) {
            return cachedId;
        }
        String requestId = resolveInbound(request.getHeader(HEADER_NAME));
        request.setAttribute(REQUEST_ATTRIBUTE, requestId);
        return requestId;
    }

    private String resolveInbound(String inbound) {
        if (inbound != null && !inbound.isBlank()
                && inbound.length() <= MAX_LENGTH
                && ALLOWED_CHARS.matcher(inbound).matches()) {
            return inbound;
        }
        return UUID.randomUUID().toString();
    }
}
