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

    private static final String REQUEST_ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";
    private static final int MAX_LENGTH = 64;
    private static final Pattern ALLOWED_CHARS = Pattern.compile("^[a-zA-Z0-9-]+$");

    /**
     * ERROR 재디스패치에서도 이 필터를 실행시킨다.
     *
     * {@link OncePerRequestFilter}의 기본값은 true라서 에러 재디스패치를 건너뛴다. 그러면
     * 최초 REQUEST 처리가 끝나며 finally에서 MDC를 지운 뒤라 에러 페이지 처리 구간의 로그에
     * requestId가 비어버린다. 장애 분석에 가장 필요한 로그가 그 구간이므로 다시 채운다.
     */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    /**
     * 비동기 재디스패치에서도 실행시킨다. 기본값 역시 true다.
     *
     * 다만 이것으로 비동기가 전부 해결되지는 않는다. MDC는 스레드 로컬이라, {@code Callable}이나
     * {@code DeferredResult}를 실제로 처리하는 작업 스레드에는 값이 전파되지 않는다. 그쪽까지
     * 채우려면 별도의 태스크 데코레이터가 필요하다 — SSE·비동기 엔드포인트를 만드는 시점의 작업이다.
     * 여기서 하는 것은 "결과를 응답으로 되돌리는 ASYNC 디스패치 구간"의 로그를 살리는 것까지다.
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
     * ERROR 재디스패치는 같은 요청의 연장이므로 최초에 정한 ID를 그대로 이어 써야 한다.
     * 여기서 새 UUID를 다시 뽑으면 하나의 요청이 두 개의 ID로 쪼개져 추적이 오히려 끊긴다.
     * 그래서 최초 1회 결정한 값을 request attribute에 남겨 재디스패치에서 되찾는다.
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
