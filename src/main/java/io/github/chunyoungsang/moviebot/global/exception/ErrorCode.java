package io.github.chunyoungsang.moviebot.global.exception;

import org.springframework.http.HttpStatus;

/**
 * 오류 코드. 공통 코드는 CommonErrorCode, 도메인 코드는 각 도메인의 exception 패키지에 enum 으로 둔다.
 * (global 이 도메인을 모르도록, 도메인 오류 코드를 global 에 모으지 않는다)
 */
public interface ErrorCode {

    /** 응답의 code 필드. enum 이름을 그대로 쓴다 (예: SUBSCRIPTION_NOT_FOUND) */
    String name();

    HttpStatus status();

    /** 사용자에게 보여줄 메시지 (내부 정보를 넣지 않는다) */
    String message();
}
