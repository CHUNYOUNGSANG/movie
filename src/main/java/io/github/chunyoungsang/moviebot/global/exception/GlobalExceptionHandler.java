package io.github.chunyoungsang.moviebot.global.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * API 오류를 { code, message } 형식으로 응답한다.
 * - 비즈니스 오류: ErrorCode 의 상태 코드와 메시지
 * - 요청 형식 오류(타입 불일치, 파라미터 누락, JSON 파싱 실패 등): 스프링 MVC 가 정한 상태 코드 + 공통 코드
 * - 그 외: 500. 예외 메시지에 내부 정보(SQL, 접속 주소 등)가 있을 수 있어 응답에 싣지 않고 로그에만 남긴다
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        log.info("비즈니스 오류 {}: {}", e.getErrorCode().name(), e.getMessage());
        return toResponse(e.getErrorCode());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("처리하지 못한 오류", e);
        return toResponse(CommonErrorCode.INTERNAL_ERROR);
    }

    /** 스프링 MVC 표준 예외(400, 404, 405, 415 ...)의 본문을 같은 형식으로 */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        CommonErrorCode code = status.is5xxServerError() ? CommonErrorCode.INTERNAL_ERROR
                : status.value() == 404 ? CommonErrorCode.NOT_FOUND
                : CommonErrorCode.INVALID_INPUT;
        if (status.is5xxServerError()) log.error("처리하지 못한 오류", e);
        else log.info("잘못된 요청 {}: {}", status.value(), e.getMessage());
        return ResponseEntity.status(status).headers(headers).body(ErrorResponse.of(code));
    }

    private static ResponseEntity<ErrorResponse> toResponse(ErrorCode code) {
        return ResponseEntity.status(code.status()).body(ErrorResponse.of(code));
    }
}
