package io.github.chunyoungsang.moviebot.global.exception;

/** API 오류 응답 본문. 클라이언트는 message 가 아니라 code 로 분기한다 */
public record ErrorResponse(String code, String message) {

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.name(), errorCode.message());
    }
}
