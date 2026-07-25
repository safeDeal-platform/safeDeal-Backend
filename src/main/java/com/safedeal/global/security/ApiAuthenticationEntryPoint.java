package com.safedeal.global.security;

import com.safedeal.global.exception.CommonErrorCode;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

// 인증되지 않은 요청이 보호된 리소스에 접근했을 때 401을 공통 응답 포맷으로 내려준다.
@Component
@RequiredArgsConstructor
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ApiErrorWriter apiErrorWriter;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException, ServletException {
        apiErrorWriter.write(response, CommonErrorCode.UNAUTHORIZED, CommonErrorCode.UNAUTHORIZED.getMessage());
    }
}
