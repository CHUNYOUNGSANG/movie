package io.github.chunyoungsang.moviebot.domain.notification.exception;

import io.github.chunyoungsang.moviebot.global.exception.ErrorCode;
import org.springframework.http.HttpStatus;

public enum NotificationErrorCode implements ErrorCode {

    SUBSCRIPTION_NOT_FOUND(HttpStatus.NOT_FOUND, "알림 신청을 찾을 수 없습니다."),
    SUBSCRIPTION_ACCESS_DENIED(HttpStatus.FORBIDDEN, "본인이 신청한 알림만 취소할 수 있습니다."),
    INVALID_SUBSCRIPTION_REQUEST(HttpStatus.BAD_REQUEST, "영화 제목(100자 이하)과 날짜를 입력해주세요."),
    TARGET_DATE_PAST(HttpStatus.BAD_REQUEST, "오늘 이후 날짜만 신청할 수 있습니다."),
    DUPLICATE_SUBSCRIPTION(HttpStatus.CONFLICT, "이미 같은 조건으로 신청한 알림이 있습니다. /list 로 확인하세요.");

    private final HttpStatus status;
    private final String message;

    NotificationErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override public HttpStatus status() { return status; }
    @Override public String message() { return message; }
}
