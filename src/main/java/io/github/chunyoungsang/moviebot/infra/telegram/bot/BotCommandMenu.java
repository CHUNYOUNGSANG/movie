package io.github.chunyoungsang.moviebot.infra.telegram.bot;

import io.github.chunyoungsang.moviebot.infra.telegram.client.TelegramClient;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 등록된 명령어로 텔레그램 "/" 자동완성 메뉴를 만든다 (명령어를 추가하면 메뉴에도 자동으로 반영) */
@Component
@RequiredArgsConstructor
public class BotCommandMenu {

    private final BotCommandDispatcher dispatcher;
    private final TelegramClient telegramClient;

    @PostConstruct
    public void register() {
        Map<String, String> menu = new LinkedHashMap<>();
        dispatcher.commands().forEach(c -> menu.put(c.name(), c.description()));
        telegramClient.setMyCommands(menu);
    }
}
