package io.github.chunyoungsang.moviebot.domain.user.exception;

import io.github.chunyoungsang.moviebot.global.exception.ErrorCode;
import org.springframework.http.HttpStatus;

public enum UserErrorCode implements ErrorCode {

    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "등록된 사용자가 없습니다. /start 를 먼저 입력하세요.");

    private final HttpStatus status;
    private final String message;

    UserErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override public HttpStatus status() { return status; }
    @Override public String message() { return message; }
}
