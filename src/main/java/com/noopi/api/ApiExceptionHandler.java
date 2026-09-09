package com.noopi.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    public record ErrorResponse(String code, String message) {}

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ErrorResponse> domain(DomainException ex) {
        log.atInfo().addKeyValue("errorCode", ex.code()).log("Request rejected");
        return ResponseEntity.status(ex.code().status)
            .body(new ErrorResponse(ex.code().name(), ex.getMessage()));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ErrorResponse> missingClient(MissingRequestHeaderException ex) {
        return domain(ErrorCode.PLAYER_NOT_IN_ROOM.exception());
    }

    // Transport errors are not domain error codes; use HTTP's standard names.
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorResponse> malformed(Exception ex) {
        return ResponseEntity.badRequest().body(new ErrorResponse("BAD_REQUEST", "요청 형식이 올바르지 않습니다."));
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    ResponseEntity<ErrorResponse> notFound(Exception ex) {
        return ResponseEntity.status(404).body(new ErrorResponse("NOT_FOUND", "요청한 경로를 찾을 수 없습니다."));
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponse> methodNotAllowed(Exception ex) {
        return ResponseEntity.status(405).body(new ErrorResponse("METHOD_NOT_ALLOWED", "지원하지 않는 요청 방식입니다."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception ex) {
        log.error("Request failed: {}", ex.getClass().getSimpleName());
        return ResponseEntity.internalServerError()
            .body(new ErrorResponse("INTERNAL_SERVER_ERROR", "요청 처리 중 오류가 발생했습니다."));
    }
}
