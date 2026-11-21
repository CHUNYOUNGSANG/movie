package io.github.chunyoungsang.moviebot.telegram.bot.command;

import io.github.chunyoungsang.moviebot.notification.dto.NotificationRegisterRequest;
import io.github.chunyoungsang.moviebot.notification.service.NotificationService;
import io.github.chunyoungsang.moviebot.telegram.bot.BotCommand;
import io.github.chunyoungsang.moviebot.telegram.bot.BotMessage;
import io.github.chunyoungsang.moviebot.telegram.client.TelegramClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** /add [영화제목] [날짜] [지점(선택)] [관종류(선택)] — 알림 신청 */
@Slf4j
@Order(2)
@Component
@RequiredArgsConstructor
public class AddCommand implements BotCommand {

    private final NotificationService notificationService;
    private final TelegramClient telegramClient;

    @Override public String name() { return "add"; }
    @Override public String description() { return "영화 알림 신청"; }

    @Override
    public void handle(BotMessage message) {
        String chatId = message.chatId();
        String[] parts = message.args().split("\\s+");
        if (parts.length < 2 || parts[0].isBlank()) {
            telegramClient.sendMessage(chatId, """
                    ❌ 형식이 올바르지 않습니다.

                    사용법: /add [영화제목] [날짜] [지점(선택)] [관종류(선택)]
                    예시: /add 스파이더맨 2026-09-04 영등포 IMAX""");
            return;
        }

        String movieTitle = parts[0];
        LocalDate targetDate;
        try {
            targetDate = LocalDate.parse(parts[1]);
        } catch (DateTimeParseException e) {
            telegramClient.sendMessage(chatId,
                    "❌ 날짜 형식이 올바르지 않습니다.\n날짜는 YYYY-MM-DD 형식으로 입력해주세요.\n예시: 2026-09-04");
            return;
        }

        String branchName     = parts.length > 2 ? parts[2] : null;
        String screenTypeName = parts.length > 3 ? parts[3] : null;

        try {
            notificationService.register(
                    new NotificationRegisterRequest(
                            chatId, movieTitle, "CGV", branchName, screenTypeName, targetDate));

            telegramClient.sendMessage(chatId, String.format("""
                    ✅ <b>알림 신청 완료!</b>

                    영화: %s
                    날짜: %s
                    지점: %s
                    관종류: %s

                    예매가 오픈되면 바로 알려드릴게요 🎬""",
                    movieTitle, targetDate,
                    branchName != null ? branchName : "전체 지점",
                    screenTypeName != null ? screenTypeName : "전체 관"
            ));
            log.info("알림 신청 (텔레그램): chatId={}, movie={}, date={}", chatId, movieTitle, targetDate);
        } catch (IllegalArgumentException e) {
            telegramClient.sendMessage(chatId, "❌ " + e.getMessage());
        }
    }
}
