package domain.telegram.service;

import domain.movie.dto.ScreeningResult;

/**
 * DIP: 메시지 발송 추상화.
 * Telegram 외 다른 채널(Slack, 이메일)로 교체 시 구현체만 바꾸면 됨.
 */
import java.util.List;

public interface MessageSender {
    /** 일반 응답 메시지. 실패해도 예외를 던지지 않는다 (로그만 남김). */
    void sendMessage(String chatId, String text);

    /**
     * 예매 오픈 알림. 동기로 발송하며, 실패하면 {@link MessageSendException} 을 던진다.
     * 호출자가 발송 결과를 기록하고 재시도 여부를 결정한다.
     */
    void sendAlerts(String chatId, List<ScreeningResult> results);
}
