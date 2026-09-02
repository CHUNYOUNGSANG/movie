package domain.telegram.service;

import domain.movie.dto.ScreeningResult;

/**
 * DIP: 메시지 발송 추상화.
 * Telegram 외 다른 채널(Slack, 이메일)로 교체 시 구현체만 바꾸면 됨.
 */
import java.util.List;

public interface MessageSender {
    void sendMessage(String chatId, String text);
    void sendAlerts(String chatId, List<ScreeningResult> results);
}
