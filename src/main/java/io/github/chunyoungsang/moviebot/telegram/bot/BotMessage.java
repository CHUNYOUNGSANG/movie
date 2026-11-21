package io.github.chunyoungsang.moviebot.telegram.bot;

/**
 * 봇이 받은 메시지 한 건.
 *
 * @param command 명령어 이름 ("/add@MyBot 위키드" → "add"). 명령어가 아니면 빈 문자열
 * @param args    명령어 뒤의 인자 ("/add 위키드 2026-12-24" → "위키드 2026-12-24")
 */
public record BotMessage(String chatId, String username, String command, String args) {

    static BotMessage parse(String chatId, String username, String text) {
        String trimmed = text == null ? "" : text.trim();
        if (!trimmed.startsWith("/")) return new BotMessage(chatId, username, "", trimmed);

        int space = trimmed.indexOf(' ');
        String head = space < 0 ? trimmed.substring(1) : trimmed.substring(1, space);
        int mention = head.indexOf('@');   // 그룹 채팅의 "/start@봇이름"
        String command = (mention < 0 ? head : head.substring(0, mention)).toLowerCase();
        String args = space < 0 ? "" : trimmed.substring(space + 1).trim();
        return new BotMessage(chatId, username, command, args);
    }
}
