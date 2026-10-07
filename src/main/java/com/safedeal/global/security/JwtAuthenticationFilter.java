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
 * 서명·만료는 로컬 연산이라 항상 검사하고, 블랙리스트만 Redis를 타므로 Redis 장애 시
 * 건너뛰는 것도 그것뿐이다({@link TokenBlacklist} 참고).
 *
 * 토큰이 없거나 유효하지 않아도 401을 직접 쓰지 않고 통과시킨다 — 이 필터는 공개 경로
 * (회원가입·로그인 등)에도 걸려서, 여기서 거절하면 공개 API가 막힌다. 최종 거절은
 * SecurityConfig와 {@link ApiAuthenticationEntryPoint}가 담당한다.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider tokenProvider;
    private final TokenBlacklist tokenBlacklist;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            tokenProvider.resolveAccess(token)
                    .filter(claims -> !tokenBlacklist.contains(claims.jti()))
                    .ifPresent(claims -> {
                        var user = new AuthenticatedUser(claims.userId(), claims.role());
                        var authentication = new UsernamePasswordAuthenticationToken(
                                user, null, List.of(new SimpleGrantedAuthority("ROLE_" + claims.role())));
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    });
        }
        filterChain.doFilter(request, response);
    }
}
