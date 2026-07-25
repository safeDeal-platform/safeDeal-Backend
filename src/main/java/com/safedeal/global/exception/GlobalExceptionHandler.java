package com.safedeal.global.exception;

import com.safedeal.global.response.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        return ResponseEntity.status(errorCode.getStatus())
                .body(ApiResponse.error(errorCode, e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        List<ApiResponse.FieldError> fieldErrors = e.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> new ApiResponse.FieldError(fieldError.getField(), fieldError.getDefaultMessage()))
                .toList();
        return ResponseEntity.status(CommonErrorCode.INVALID_INPUT.getStatus())
                .body(ApiResponse.error(CommonErrorCode.INVALID_INPUT, "입력값이 올바르지 않습니다.", fieldErrors));
    }

    // @RequestParam/@PathVariable에 붙은 제약(@Min 등) 위반. Spring 6.1+에서는 컨트롤러
    // 메서드 파라미터 검증 실패가 이 예외로 올라온다.
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleHandlerMethodValidationException(HandlerMethodValidationException e) {
        List<ApiResponse.FieldError> fieldErrors = e.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new ApiResponse.FieldError(
                                result.getMethodParameter().getParameterName(),
                                error.getDefaultMessage())))
                .toList();
        return ResponseEntity.status(CommonErrorCode.INVALID_INPUT.getStatus())
                .body(ApiResponse.error(CommonErrorCode.INVALID_INPUT, "입력값이 올바르지 않습니다.", fieldErrors));
    }

    // 서비스 계층 등에서 직접 검증기를 호출한 경우
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolationException(ConstraintViolationException e) {
        List<ApiResponse.FieldError> fieldErrors = e.getConstraintViolations().stream()
                .map(violation -> new ApiResponse.FieldError(
                        violation.getPropertyPath().toString(),
                        violation.getMessage()))
                .toList();
        return ResponseEntity.status(CommonErrorCode.INVALID_INPUT.getStatus())
                .body(ApiResponse.error(CommonErrorCode.INVALID_INPUT, "입력값이 올바르지 않습니다.", fieldErrors));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingServletRequestParameterException(
            MissingServletRequestParameterException e) {
        String message = "필수 파라미터 '%s'이(가) 누락되었습니다.".formatted(e.getParameterName());
        return ResponseEntity.status(CommonErrorCode.INVALID_INPUT.getStatus())
                .body(ApiResponse.error(CommonErrorCode.INVALID_INPUT, message));
    }

    // @ModelAttribute 바인딩 실패
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<Void>> handleBindException(BindException e) {
        List<ApiResponse.FieldError> fieldErrors = e.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> new ApiResponse.FieldError(fieldError.getField(), fieldError.getDefaultMessage()))
                .toList();
        return ResponseEntity.status(CommonErrorCode.INVALID_INPUT.getStatus())
                .body(ApiResponse.error(CommonErrorCode.INVALID_INPUT, "입력값이 올바르지 않습니다.", fieldErrors));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMediaTypeNotSupportedException(
            HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.status(CommonErrorCode.UNSUPPORTED_MEDIA_TYPE.getStatus())
                .body(ApiResponse.error(CommonErrorCode.UNSUPPORTED_MEDIA_TYPE,
                        CommonErrorCode.UNSUPPORTED_MEDIA_TYPE.getMessage()));
    }

    // UNIQUE·FK 등 제약 위반. 예외 메시지에 실제 데이터와 제약 이름이 들어 있어
    // (예: Duplicate entry 'a@b.com' for key 'users.email') 응답에는 절대 노출하지 않고
    // 로그에만 남긴다.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolationException(DataIntegrityViolationException e) {
        log.warn("데이터 제약 조건 위반", e);
        return ResponseEntity.status(CommonErrorCode.CONFLICT.getStatus())
                .body(ApiResponse.error(CommonErrorCode.CONFLICT, CommonErrorCode.CONFLICT.getMessage()));
    }

    // 낙관적 락 충돌. 재시도로 해결될 수 있는 일시적 충돌이라 별도 코드로 구분한다.
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLockingFailureException(
            OptimisticLockingFailureException e) {
        log.warn("낙관적 락 충돌", e);
        return ResponseEntity.status(CommonErrorCode.CONCURRENT_MODIFICATION.getStatus())
                .body(ApiResponse.error(CommonErrorCode.CONCURRENT_MODIFICATION,
                        CommonErrorCode.CONCURRENT_MODIFICATION.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(CommonErrorCode.PAYLOAD_TOO_LARGE.getStatus())
                .body(ApiResponse.error(CommonErrorCode.PAYLOAD_TOO_LARGE,
                        CommonErrorCode.PAYLOAD_TOO_LARGE.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        return ResponseEntity.status(CommonErrorCode.INVALID_INPUT.getStatus())
                .body(ApiResponse.error(CommonErrorCode.INVALID_INPUT, "요청 본문을 읽을 수 없습니다."));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException e) {
        String message = "'%s' 파라미터의 값 '%s'이(가) 올바르지 않습니다.".formatted(e.getName(), e.getValue());
        return ResponseEntity.status(CommonErrorCode.INVALID_INPUT.getStatus())
                .body(ApiResponse.error(CommonErrorCode.INVALID_INPUT, message));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFoundException(NoResourceFoundException e) {
        return ResponseEntity.status(CommonErrorCode.RESOURCE_NOT_FOUND.getStatus())
                .body(ApiResponse.error(CommonErrorCode.RESOURCE_NOT_FOUND, CommonErrorCode.RESOURCE_NOT_FOUND.getMessage()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpRequestMethodNotSupportedException(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(CommonErrorCode.METHOD_NOT_ALLOWED.getStatus())
                .body(ApiResponse.error(CommonErrorCode.METHOD_NOT_ALLOWED, CommonErrorCode.METHOD_NOT_ALLOWED.getMessage()));
    }

    // 메서드 보안(@PreAuthorize 등)에서 던지는 AccessDeniedException은 필터 체인의
    // ApiAccessDeniedHandler를 타지 않고 컨트롤러/서비스 호출 스택에서 바로 올라오므로
    // catch-all(Exception.class)이 500으로 삼켜버리기 전에 여기서 먼저 잡는다.
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDeniedException(AccessDeniedException e) {
        return ResponseEntity.status(CommonErrorCode.FORBIDDEN.getStatus())
                .body(ApiResponse.error(CommonErrorCode.FORBIDDEN, CommonErrorCode.FORBIDDEN.getMessage()));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthenticationException(AuthenticationException e) {
        return ResponseEntity.status(CommonErrorCode.UNAUTHORIZED.getStatus())
                .body(ApiResponse.error(CommonErrorCode.UNAUTHORIZED, CommonErrorCode.UNAUTHORIZED.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        log.error("예상하지 못한 서버 오류가 발생했습니다.", e);
        return ResponseEntity.status(CommonErrorCode.INTERNAL_ERROR.getStatus())
                .body(ApiResponse.error(CommonErrorCode.INTERNAL_ERROR, CommonErrorCode.INTERNAL_ERROR.getMessage()));
    }
}
