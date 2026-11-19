package domain.telegram.service;

/**
 * 메시지 발송 실패. 호출자가 실패를 기록하고 재시도할 수 있도록 던진다.
 */
public class MessageSendException extends RuntimeException {

    public MessageSendException(String message, Throwable cause) {
        super(message, cause);
    }
}
