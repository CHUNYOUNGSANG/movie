package io.github.chunyoungsang.moviebot.infra.telegram.bot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 텔레그램 update 를 해석해 이름이 맞는 명령어에 넘긴다. 모르는 명령어나 일반 텍스트는 도움말로 응답한다.
 * 명령어 목록은 스프링이 BotCommand 빈을 모아 주입하므로, 명령어를 추가해도 이 클래스는 바뀌지 않는다.
 */
@Slf4j
@Component
public class BotCommandDispatcher {

    static final String FALLBACK = "help";

    private final Map<String, BotCommand> commands = new LinkedHashMap<>();
    private final BotCommand fallback;

    public BotCommandDispatcher(List<BotCommand> commands) {
        for (BotCommand command : commands) {
            if (this.commands.putIfAbsent(command.name(), command) != null) {
                throw new IllegalStateException("명령어 이름 중복: /" + command.name());
            }
        }
        this.fallback = this.commands.get(FALLBACK);
        if (fallback == null) throw new IllegalStateException("/" + FALLBACK + " 명령어가 필요합니다");
    }

    /** 등록된 명령어 (메뉴 노출 순서) */
    public List<BotCommand> commands() {
        return List.copyOf(commands.values());
    }

    @SuppressWarnings("unchecked")
    public void processUpdate(Map<String, Object> update) {
        Map<String, Object> message = (Map<String, Object>) update.get("message");
        if (message == null) return;
        Map<String, Object> chat = (Map<String, Object>) message.get("chat");
        if (chat == null) return;

        BotMessage botMessage = BotMessage.parse(
                String.valueOf(chat.get("id")),
                (String) chat.getOrDefault("username", ""),
                (String) message.getOrDefault("text", ""));
        commands.getOrDefault(botMessage.command(), fallback).handle(botMessage);
    }
}
