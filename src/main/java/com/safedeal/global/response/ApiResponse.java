package com.safedeal.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.safedeal.global.exception.ErrorCode;
import lombok.Getter;
import org.slf4j.MDC;

import java.util.List;

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private final boolean success;
    private final T data;
    private final ErrorDetail error;

    private ApiResponse(boolean success, T data, ErrorDetail error) {
        this.success = success;
        this.data = data;
        this.error = error;
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static <T> ApiResponse<T> success() {
        return new ApiResponse<>(true, null, null);
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode, String message) {
        return new ApiResponse<>(false, null, new ErrorDetail(errorCode.getCode(), message, null));
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode, String message, List<FieldError> fieldErrors) {
        return new ApiResponse<>(false, null, new ErrorDetail(errorCode.getCode(), message, fieldErrors));
    }

    @Getter
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ErrorDetail {

        private final String code;
        private final String message;
        private final List<FieldError> fieldErrors;
        // MDC 키 "requestId"는 다른 담당자가 만드는 RequestIdFilter가 채워 넣는다.
        // 여기서는 읽기만 한다.
        private final String requestId;

        private ErrorDetail(String code, String message, List<FieldError> fieldErrors) {
            this.code = code;
            this.message = message;
            this.fieldErrors = fieldErrors;
            this.requestId = MDC.get("requestId");
        }
    }

    public record FieldError(String field, String message) {
    }
}
