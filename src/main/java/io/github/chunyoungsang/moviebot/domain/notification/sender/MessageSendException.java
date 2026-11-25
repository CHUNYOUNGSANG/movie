package io.github.chunyoungsang.moviebot.domain.notification.sender;

/**
 * 메시지 발송 실패. 호출자가 실패를 기록하고 재시도 여부를 결정할 수 있도록 던진다.
 */
import java.time.Duration;

public class MessageSendException extends RuntimeException {

    /** false 면 재시도해도 성공할 수 없는 실패 (예: 사용자가 봇을 차단함, 존재하지 않는 chat) */
    private final boolean retryable;

    /** 429(Too Many Requests)일 때 서버가 알려준 대기 시간. 그 외에는 null */
    private final Duration retryAfter;

    /** true 면 받는 사람에게 더 이상 보낼 수 없다 (봇을 차단함, 탈퇴한 계정, 없는 chat). 재시도하지 않는다 */
    private final boolean recipientUnavailable;

    public MessageSendException(String message, Throwable cause) {
        this(message, cause, true);
    }

    public MessageSendException(String message, Throwable cause, boolean retryable) {
        this(message, cause, retryable, null);
    }

    public MessageSendException(String message, Throwable cause, boolean retryable, Duration retryAfter) {
        this(message, cause, retryable, retryAfter, false);
    }

    private MessageSendException(String message, Throwable cause, boolean retryable, Duration retryAfter,
                                 boolean recipientUnavailable) {
        super(message, cause);
        this.retryable = retryable;
        this.retryAfter = retryAfter;
        this.recipientUnavailable = recipientUnavailable;
    }

    /** 받는 사람에게 더 이상 보낼 수 없음 (재시도 불가). 호출자는 받는 사람의 구독을 해제한다 */
    public static MessageSendException recipientUnavailable(String message) {
        return new MessageSendException(message, null, false, null, true);
    }

    public boolean isRecipientUnavailable() {
        return recipientUnavailable;
    }

    /** 속도 제한(429)으로 거부됨: 메시지 문제가 아니므로 시도 횟수를 소모하지 않고 retryAfter 후 다시 보낸다 */
    public boolean isRateLimited() {
        return retryAfter != null;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
