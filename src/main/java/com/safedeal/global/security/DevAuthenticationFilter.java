package com.safedeal.global.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

// JWT가 없는 동안 X-Dev-User-Id/X-Dev-Role 헤더로 SecurityContext를 채우는 임시 우회 필터.
// local + app.security.dev-auth-enabled=true 일 때만 등록되고, 운영엔 이 빈 자체가 없다.
@Slf4j
@Component
@Profile("local")
@ConditionalOnProperty(prefix = "app.security", name = "dev-auth-enabled", havingValue = "true")
public class DevAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER_USER_ID = "X-Dev-User-Id";
    private static final String HEADER_ROLE = "X-Dev-Role";
    private static final String DEFAULT_ROLE = "USER";
    // ADMIN은 의도적으로 제외한다. 관리자 기능은 개발용 우회로 테스트하지 않는다.
    private static final Set<String> ALLOWED_ROLES = Set.of("USER");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // 이미 인증됐으면 헤더 우회로 덮어쓰지 않는다 — 진짜 토큰이 항상 이겨야 한다.
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        String userId = request.getHeader(HEADER_USER_ID);
        if (userId == null || userId.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        // userId가 숫자가 아니면 인증하지 않고 통과시킨다 — 절반만 인증된 상태보다
        // 401로 명확히 드러나는 편이 낫다.
        long parsedUserId;
        try {
            parsedUserId = Long.parseLong(userId.trim());
        } catch (NumberFormatException e) {
            log.warn("개발용 인증 헤더의 사용자 ID가 숫자가 아니라 무시함 (local 전용)");
            filterChain.doFilter(request, response);
            return;
        }

        // 헤더 값을 그대로 권한으로 쓰면 ADMIN을 스스로 부여할 수 있어 허용 목록에 없으면
        // 기본 권한만 준다(null 먼저 확인 — contains(null)은 NPE).
        String requestedRole = request.getHeader(HEADER_ROLE);
        String role = (requestedRole != null && ALLOWED_ROLES.contains(requestedRole))
                ? requestedRole
                : DEFAULT_ROLE;

        // principal은 반드시 AuthenticatedUser로 넣는다 — 컨트롤러가
        // @AuthenticationPrincipal AuthenticatedUser 로 꺼내는 계약(해당 클래스 주석 참고).
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(parsedUserId, role), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        // 식별자를 그대로 로그에 남기면 개인정보가 로그 수집기까지 흘러간다 — 발생 사실만 기록.
        log.warn("개발용 인증 우회 사용됨 - role={} (local 전용, 운영 배포 금지)", role);

        filterChain.doFilter(request, response);
    }
}
