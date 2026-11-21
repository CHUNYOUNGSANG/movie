package io.github.chunyoungsang.moviebot.domain.notification.sender;

/**
 * 알림 발송 포트 (DIP).
 * 알림 도메인이 필요로 하는 "보낸다"만 정의하고, 실제 채널(텔레그램 등)은 외부 연동 패키지가 구현한다.
 * → 알림 도메인은 텔레그램을 알지 못하고, 채널을 바꿔도 이 인터페이스의 구현체만 바꾸면 된다.
 */
public interface MessageSender {

    /**
     * 메시지를 동기로 발송하며, 실패하면 {@link MessageSendException} 을 던진다.
     * 호출자(NotificationDispatcher)가 발송 결과를 기록하고 재시도 여부를 결정한다.
     */
    void deliver(String chatId, String text);
}
