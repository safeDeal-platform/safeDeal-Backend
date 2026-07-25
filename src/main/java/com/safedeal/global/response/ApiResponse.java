package com.safedeal.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.safedeal.global.exception.ErrorCode;
import lombok.Getter;

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
        return new ApiResponse<>(false, null, new ErrorDetail(errorCode.getCode(), message));
    }

    @Getter
    public static class ErrorDetail {

        private final String code;
        private final String message;

        private ErrorDetail(String code, String message) {
            this.code = code;
            this.message = message;
        }
    }
}
