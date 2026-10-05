package com.safedeal.global.security;

import com.safedeal.global.exception.ErrorCode;
import com.safedeal.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

// 필터 체인 예외(인증 실패/인가 거부)는 GlobalExceptionHandler를 안 타서 여기서 직접 write한다.
// ObjectMapper는 Jackson 3(tools.jackson) 쪽을 쓴다 — com.fasterxml 버전 빈은 없다.
@Component
@RequiredArgsConstructor
public class ApiErrorWriter {

    private final ObjectMapper objectMapper;

    public void write(HttpServletResponse response, ErrorCode errorCode, String message) throws IOException {
        response.setStatus(errorCode.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(errorCode, message)));
    }
}
