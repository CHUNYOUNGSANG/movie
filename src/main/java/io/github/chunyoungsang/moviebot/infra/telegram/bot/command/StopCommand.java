package io.github.chunyoungsang.moviebot.infra.telegram.bot.command;

import io.github.chunyoungsang.moviebot.infra.telegram.bot.BotCommand;
import io.github.chunyoungsang.moviebot.infra.telegram.bot.BotMessage;
import io.github.chunyoungsang.moviebot.infra.telegram.client.TelegramClient;
import io.github.chunyoungsang.moviebot.domain.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** /stop — 알림 구독 해제 */
@Slf4j
@Order(6)
@Component
@RequiredArgsConstructor
public class StopCommand implements BotCommand {

    private final UserService userService;
    private final TelegramClient telegramClient;

    @Override public String name() { return "stop"; }
    @Override public String description() { return "알림 구독 해제"; }

    @Override
    public void handle(BotMessage message) {
        String chatId = message.chatId();
        userService.deactivate(chatId).ifPresentOrElse(
                user -> {
                    log.info("구독 해제: chatId={}", chatId);
                    telegramClient.sendMessage(chatId,
                            "🔕 구독이 해제됐습니다. 다시 받으시려면 /start 를 입력하세요.");
                },
                () -> telegramClient.sendMessage(chatId, "구독 중이 아닙니다.")
        );
    }
}
