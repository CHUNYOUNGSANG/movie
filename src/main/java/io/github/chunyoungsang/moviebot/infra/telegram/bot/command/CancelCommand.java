package io.github.chunyoungsang.moviebot.infra.telegram.bot.command;

import io.github.chunyoungsang.moviebot.domain.notification.exception.NotificationErrorCode;
import io.github.chunyoungsang.moviebot.global.exception.BusinessException;

import io.github.chunyoungsang.moviebot.domain.notification.service.NotificationService;
import io.github.chunyoungsang.moviebot.infra.telegram.bot.BotCommand;
import io.github.chunyoungsang.moviebot.infra.telegram.bot.BotMessage;
import io.github.chunyoungsang.moviebot.infra.telegram.client.TelegramClient;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** /cancel [번호] — 알림 취소 (본인 것만) */
@Order(5)
@Component
@RequiredArgsConstructor
public class CancelCommand implements BotCommand {

    private final NotificationService notificationService;
    private final TelegramClient telegramClient;

    @Override public String name() { return "cancel"; }
    @Override public String description() { return "알림 취소"; }

    @Override
    public void handle(BotMessage message) {
        String chatId = message.chatId();
        String idStr = message.args();
        if (idStr.isBlank()) {
            telegramClient.sendMessage(chatId,
                    "취소할 번호를 입력해주세요.\n예시: /cancel 1\n\n목록 확인: /list");
            return;
        }
        try {
            Long subscriptionId = Long.parseLong(idStr);
            notificationService.cancel(subscriptionId, chatId);
            telegramClient.sendMessage(chatId, "🗑 #" + subscriptionId + " 알림이 취소됐습니다.");
        } catch (NumberFormatException e) {
            telegramClient.sendMessage(chatId,
                    "❌ 번호가 올바르지 않습니다.\n예시: /cancel 1\n\n목록 확인: /list");
        } catch (BusinessException e) {
            telegramClient.sendMessage(chatId, e.getErrorCode() == NotificationErrorCode.SUBSCRIPTION_NOT_FOUND
                    ? "❌ 해당 번호의 알림을 찾을 수 없습니다.\n현재 목록을 확인해주세요: /list"
                    : "❌ " + e.getErrorCode().message());
        }
    }
}
