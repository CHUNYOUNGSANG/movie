package io.github.chunyoungsang.moviebot.domain.notification.exception;

import io.github.chunyoungsang.moviebot.global.exception.ErrorCode;
import org.springframework.http.HttpStatus;

public enum NotificationErrorCode implements ErrorCode {

    SUBSCRIPTION_NOT_FOUND(HttpStatus.NOT_FOUND, "알림 신청을 찾을 수 없습니다."),
    SUBSCRIPTION_ACCESS_DENIED(HttpStatus.FORBIDDEN, "본인이 신청한 알림만 취소할 수 있습니다.");

    private final HttpStatus status;
    private final String message;

    NotificationErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override public HttpStatus status() { return status; }
    @Override public String message() { return message; }
}
