package com.safedeal.global.security;

import com.safedeal.global.exception.ErrorCode;
import com.safedeal.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

// 필터 체인 단계(인증 실패/인가 거부)에서 발생하는 예외는 DispatcherServlet 이전이라
// GlobalExceptionHandler(@RestControllerAdvice)를 타지 않는다. 그래서 같은 ApiResponse
// 포맷을 여기서 직접 write한다.
// ObjectMapper는 Boot 4가 자동 구성하는 Jackson 3(tools.jackson) 쪽을 주입받는다.
// com.fasterxml.jackson.databind.ObjectMapper 빈은 존재하지 않는다.
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
