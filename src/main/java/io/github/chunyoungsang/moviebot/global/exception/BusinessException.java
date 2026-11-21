package io.github.chunyoungsang.moviebot.global.exception;

/**
 * 비즈니스 규칙 위반 (없는 알림 취소, 남의 알림 취소 등). 요청한 쪽에 알려야 하는 "예상된" 오류.
 *
 * 예외의 종류
 * - BusinessException          : 사용자에게 알릴 오류. ErrorCode 의 상태 코드와 메시지로 응답한다 (API, 봇 공통)
 * - 포트 계약 예외             : 외부 연동 실패를 도메인에 알린다. 포트 옆에 둔다
 *                                (CrawlException: 실패 원인, MessageSendException: 재시도 여부·대기 시간)
 * - IllegalStateException 등  : 프로그래밍·설정 오류. 잡지 않고 500 으로 처리, 메시지는 로그에만 남긴다
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    /** detail 은 로그용 (응답에는 ErrorCode 의 메시지만 나간다) */
    public BusinessException(ErrorCode errorCode, String detail) {
        super(errorCode.message() + " (" + detail + ")");
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
