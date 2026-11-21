package io.github.chunyoungsang.moviebot.infra.telegram.auth;

import io.github.chunyoungsang.moviebot.global.exception.ErrorCode;
import org.springframework.http.HttpStatus;

public enum TelegramAuthErrorCode implements ErrorCode {

    TELEGRAM_LOGIN_INVALID(HttpStatus.UNAUTHORIZED, "텔레그램 로그인 정보가 올바르지 않습니다."),
    TELEGRAM_LOGIN_EXPIRED(HttpStatus.UNAUTHORIZED, "텔레그램 로그인이 만료됐습니다. 다시 로그인해주세요.");

    private final HttpStatus status;
    private final String message;

    TelegramAuthErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override public HttpStatus status() { return status; }
    @Override public String message() { return message; }
}
