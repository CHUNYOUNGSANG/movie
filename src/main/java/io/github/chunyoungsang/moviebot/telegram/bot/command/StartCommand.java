package io.github.chunyoungsang.moviebot.telegram.bot.command;

import io.github.chunyoungsang.moviebot.telegram.bot.BotCommand;
import io.github.chunyoungsang.moviebot.telegram.bot.BotMessage;
import io.github.chunyoungsang.moviebot.telegram.client.TelegramClient;
import io.github.chunyoungsang.moviebot.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** /start — 알림 구독 시작 (신규 가입 또는 재활성화) */
@Slf4j
@Order(1)
@Component
@RequiredArgsConstructor
public class StartCommand implements BotCommand {

    private final UserService userService;
    private final TelegramClient telegramClient;

    @Override public String name() { return "start"; }
    @Override public String description() { return "알림 구독 시작"; }

    @Override
    public void handle(BotMessage message) {
        String chatId = message.chatId();
        UserService.ActivationResult result = userService.activate(chatId, message.username());
        switch (result.status()) {
            case CREATED -> {
                log.info("신규 구독: chatId={}, username={}", chatId, message.username());
                telegramClient.sendMessage(chatId, "🎉 구독 완료!\n\n" + HelpCommand.TEXT);
            }
            case REACTIVATED -> telegramClient.sendMessage(chatId, "✅ 구독이 재활성화됐습니다!\n\n" + HelpCommand.TEXT);
            case ALREADY_ACTIVE -> telegramClient.sendMessage(chatId, "이미 구독 중입니다 😊\n\n" + HelpCommand.TEXT);
        }
    }
}
