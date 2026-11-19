package domain.telegram.service;

/**
 * 메시지 발송 실패. 호출자가 실패를 기록하고 재시도 여부를 결정할 수 있도록 던진다.
 */
public class MessageSendException extends RuntimeException {

    /** false 면 재시도해도 성공할 수 없는 실패 (예: 사용자가 봇을 차단함, 존재하지 않는 chat) */
    private final boolean retryable;

    public MessageSendException(String message, Throwable cause) {
        this(message, cause, true);
    }

    public MessageSendException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
