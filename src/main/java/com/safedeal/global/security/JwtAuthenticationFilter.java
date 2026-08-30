package com.safedeal.global.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authorization: Bearer 헤더의 access 토큰을 principal로 바꿔 SecurityContext에 넣는다.
 *
 * 서명·만료 검증은 서버 메모리의 비밀키로 하는 로컬 연산이라 외부 저장소가 필요 없다.
 *
 * 토큰이 없거나 유효하지 않으면 인증하지 않고 그냥 통과시킨다. 여기서 401을 직접 쓰지 않는
 * 이유: 이 필터는 공개 경로(회원가입·로그인·매물 목록)에도 걸리므로, 토큰이 없다는 것만으로
 * 거절하면 공개 API가 전부 막힌다. 접근 거절은 SecurityConfig의 authorizeHttpRequests와
 * ApiAuthenticationEntryPoint가 담당한다.
 *
 * principal은 반드시 {@link AuthenticatedUser}로 넣는다 — 컨트롤러가
 * {@code @AuthenticationPrincipal AuthenticatedUser}로 꺼내는 계약(해당 클래스 주석 참고).
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider tokenProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            tokenProvider.resolveAccess(token).ifPresent(claims -> {
                var user = new AuthenticatedUser(claims.userId(), claims.role());
                var authentication = new UsernamePasswordAuthenticationToken(
                        user, null, List.of(new SimpleGrantedAuthority("ROLE_" + claims.role())));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            });
        }
        filterChain.doFilter(request, response);
    }
}
