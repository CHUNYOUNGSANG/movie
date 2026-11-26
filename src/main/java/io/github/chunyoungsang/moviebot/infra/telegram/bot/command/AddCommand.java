package io.github.chunyoungsang.moviebot.infra.telegram.bot.command;

import io.github.chunyoungsang.moviebot.global.exception.BusinessException;

import io.github.chunyoungsang.moviebot.domain.notification.dto.NotificationRegisterRequest;
import io.github.chunyoungsang.moviebot.domain.notification.service.NotificationService;
import io.github.chunyoungsang.moviebot.infra.telegram.bot.BotCommand;
import io.github.chunyoungsang.moviebot.infra.telegram.bot.BotMessage;
import io.github.chunyoungsang.moviebot.infra.telegram.client.TelegramClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;

/** /add [영화제목] [날짜] [지점(선택)] [관종류(선택)] — 알림 신청 */
@Slf4j
@Order(2)
@Component
@RequiredArgsConstructor
public class AddCommand implements BotCommand {

    private final NotificationService notificationService;
    private final TelegramClient telegramClient;

    @Override public String name() { return "add"; }
    @Override public String description() { return "영화 알림 신청 · 영화제목 날짜 [지점] [관]"; }

    private static final String USAGE = """
            사용법: /add [영화제목] [날짜] [지점(선택)] [관종류(선택)]
            예시: /add 슬픈 연대 2026-09-04 영등포 IMAX

            · 날짜는 YYYY-MM-DD
            · 제목에 띄어쓰기가 있어도 됩니다 (날짜 앞까지가 제목)""";

    @Override
    public void handle(BotMessage message) {
        String chatId = message.chatId();
        String args = message.args().strip();
        if (args.isEmpty()) {   // 메뉴에서 /add 만 누른 경우: 오류가 아니라 입력 안내
            telegramClient.sendMessage(chatId, "🎬 <b>알림 신청</b>\n\n" + USAGE);
            return;
        }

        // 날짜(YYYY-MM-DD) 칸을 기준으로 앞은 제목, 뒤는 지점·관종류. 제목에 공백이 있어도 된다
        String[] parts = args.split("\\s+");
        if (parts.length < 2 || isDate(parts[0])) {   // 제목이나 날짜가 빠짐
            telegramClient.sendMessage(chatId, "❌ 형식이 올바르지 않습니다.\n\n" + USAGE);
            return;
        }
        int dateIndex = -1;
        LocalDate targetDate = null;
        for (int i = 1; i < parts.length && targetDate == null; i++) {
            try {
                targetDate = LocalDate.parse(parts[i]);
                dateIndex = i;
            } catch (DateTimeParseException ignored) {
                // 날짜가 아니면 제목의 일부
            }
        }
        if (targetDate == null) {
            telegramClient.sendMessage(chatId,
                    "❌ 날짜 형식이 올바르지 않습니다.\n날짜는 YYYY-MM-DD 형식으로 입력해주세요.\n예시: 2026-09-04");
            return;
        }

        String movieTitle = String.join(" ", Arrays.copyOfRange(parts, 0, dateIndex));
        String branchName     = parts.length > dateIndex + 1 ? parts[dateIndex + 1] : null;
        String screenTypeName = parts.length > dateIndex + 2 ? parts[dateIndex + 2] : null;

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
        } catch (BusinessException e) {   // 가입 전 사용자 등 (예상하지 못한 오류는 폴러가 로그로 남김)
            telegramClient.sendMessage(chatId, "❌ " + e.getErrorCode().message());
        }
    }

    private static boolean isDate(String text) {
        try {
            LocalDate.parse(text);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
