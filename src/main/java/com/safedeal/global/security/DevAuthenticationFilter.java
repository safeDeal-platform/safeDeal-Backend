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

// JWT 인증이 아직 없는 시점에 팀원들이 보호된 API를 테스트할 수 있게 해주는 임시 필터.
// 요청 헤더 X-Dev-User-Id / X-Dev-Role(기본 USER)을 읽어 SecurityContext에 인증 정보를 넣는다.
// 실제 JWT 필터가 만들어지면(SecurityConfig의 안내 주석 위치) 이 필터는 제거될 예정이다.
// local 프로파일 + app.security.dev-auth-enabled=true 일 때만 빈으로 등록되며,
// 운영/기본 프로파일에는 이 빈 자체가 존재하지 않는다.
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
        // JWT 필터가 이미 인증을 끝냈다면 헤더 우회로 덮어쓰지 않는다. 진짜 토큰이 항상 이겨야
        // 하고, 그래야 팀이 dev 헤더에서 실제 로그인으로 옮겨가는 동안 둘을 함께 켜둘 수 있다.
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        String userId = request.getHeader(HEADER_USER_ID);
        if (userId == null || userId.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        // principal 계약(AuthenticatedUser)은 userId를 Long으로 요구한다. 숫자가 아닌 값은
        // 인증하지 않고 통과시킨다 — 잘못된 헤더로 절반만 인증된 상태를 만드는 것보다
        // 401로 명확히 드러나는 편이 낫다.
        long parsedUserId;
        try {
            parsedUserId = Long.parseLong(userId.trim());
        } catch (NumberFormatException e) {
            log.warn("개발용 인증 헤더의 사용자 ID가 숫자가 아니라 무시함 (local 전용)");
            filterChain.doFilter(request, response);
            return;
        }

        // 요청 헤더 값을 그대로 권한으로 만들면 ADMIN 등 임의 권한을 스스로 부여할 수 있다.
        // 허용 목록에 없는 값은 조용히 무시하고 기본 권한만 준다.
        // Set.of(...)는 불변 집합이라 contains(null)에서 NPE가 나므로 null을 먼저 거른다.
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
        // 클라이언트가 보낸 식별자를 그대로 로그에 남기면 개인정보성 값이 로그 수집기까지
        // 흘러가고 임의 문자열로 로그를 오염시킬 수 있다. 발생 사실만 기록한다.
        log.warn("개발용 인증 우회 사용됨 - role={} (local 전용, 운영 배포 금지)", role);

        filterChain.doFilter(request, response);
    }
}
