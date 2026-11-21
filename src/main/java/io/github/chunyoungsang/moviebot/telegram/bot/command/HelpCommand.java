package io.github.chunyoungsang.moviebot.telegram.bot.command;

import io.github.chunyoungsang.moviebot.telegram.bot.BotCommand;
import io.github.chunyoungsang.moviebot.telegram.bot.BotMessage;
import io.github.chunyoungsang.moviebot.telegram.client.TelegramClient;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** /help — 사용법 안내. 모르는 명령어나 일반 텍스트에도 이 응답을 보낸다 */
@Order(7)
@Component
@RequiredArgsConstructor
public class HelpCommand implements BotCommand {

    static final String TEXT = """
            🎬 <b>영화 예매 알리미 사용법</b>

            <b>기본 명령어</b>
            /start   — 알림 구독 시작
            /stop    — 알림 구독 해제
            /list    — 대기 중인 알림 목록
            /history — 알림 기록 (발송 결과 포함)
            /help    — 사용법 안내

            <b>알림 신청</b>
            /add [영화제목] [날짜] [지점(선택)] [관종류(선택)]

            <b>예시</b>
            /add 스파이더맨 2026-09-04
            /add 스파이더맨 2026-09-04 영등포
            /add 스파이더맨 2026-09-04 영등포 IMAX

            <b>알림 취소</b>
            /cancel [번호]  ← /list 에서 확인한 번호

            📌 예매가 오픈되면 자동으로 알림을 드립니다!
            """;

    private final TelegramClient telegramClient;

    @Override public String name() { return "help"; }
    @Override public String description() { return "사용법 안내"; }

    @Override
    public void handle(BotMessage message) {
        telegramClient.sendMessage(message.chatId(), TEXT);
    }
}
