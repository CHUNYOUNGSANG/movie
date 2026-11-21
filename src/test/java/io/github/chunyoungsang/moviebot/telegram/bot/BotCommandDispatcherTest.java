package io.github.chunyoungsang.moviebot.telegram.bot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BotCommandDispatcher")
class BotCommandDispatcherTest {

    /** 받은 메시지를 기록만 하는 가짜 명령어 */
    static class RecordingCommand implements BotCommand {
        final String name;
        final List<BotMessage> received = new ArrayList<>();

        RecordingCommand(String name) { this.name = name; }

        @Override public String name() { return name; }
        @Override public String description() { return name + " 설명"; }
        @Override public void handle(BotMessage message) { received.add(message); }
    }

    private final RecordingCommand start = new RecordingCommand("start");
    private final RecordingCommand add = new RecordingCommand("add");
    private final RecordingCommand help = new RecordingCommand("help");
    private final BotCommandDispatcher dispatcher = new BotCommandDispatcher(List.of(start, add, help));

    @Test
    @DisplayName("명령어 이름으로 분배하고, 명령어 뒤의 내용을 인자로 넘긴다")
    void routesByNameWithArgs() {
        dispatcher.processUpdate(update("111", "/add 위키드 2026-12-24 강남"));

        assertThat(add.received).singleElement().satisfies(m -> {
            assertThat(m.chatId()).isEqualTo("111");
            assertThat(m.args()).isEqualTo("위키드 2026-12-24 강남");
        });
    }

    @Test
    @DisplayName("그룹 채팅의 /start@봇이름 도 같은 명령어로 처리한다")
    void stripsBotMention() {
        dispatcher.processUpdate(update("111", "/start@MovieAlertBot"));

        assertThat(start.received).hasSize(1);
    }

    @Test
    @DisplayName("모르는 명령어나 일반 텍스트는 도움말로 응답한다")
    void unknownGoesToHelp() {
        dispatcher.processUpdate(update("111", "/unknown"));
        dispatcher.processUpdate(update("111", "안녕하세요"));

        assertThat(help.received).hasSize(2);
        assertThat(start.received).isEmpty();
    }

    @Test
    @DisplayName("[OCP] 새 명령어는 BotCommand 구현만 추가하면 분배되고 메뉴 목록에도 들어간다")
    void newCommandNeedsNoDispatcherChange() {
        RecordingCommand remind = new RecordingCommand("remind");
        BotCommandDispatcher extended = new BotCommandDispatcher(List.of(start, add, remind, help));

        extended.processUpdate(update("111", "/remind 3"));

        assertThat(remind.received).singleElement().extracting(BotMessage::args).isEqualTo("3");
        assertThat(extended.commands()).extracting(BotCommand::name).containsExactly("start", "add", "remind", "help");
    }

    @Test
    @DisplayName("명령어 이름이 겹치면 시작할 때 실패한다")
    void duplicateNameFailsFast() {
        assertThatThrownBy(() -> new BotCommandDispatcher(List.of(start, new RecordingCommand("start"), help)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("/start");
    }

    @Test
    @DisplayName("도움말(/help) 명령어가 없으면 시작할 때 실패한다")
    void requiresHelpFallback() {
        assertThatThrownBy(() -> new BotCommandDispatcher(List.of(start)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("/help");
    }

    private static Map<String, Object> update(String chatId, String text) {
        return Map.of("message", Map.of("chat", Map.of("id", chatId, "username", "u" + chatId), "text", text));
    }
}
