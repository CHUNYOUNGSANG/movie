package io.github.chunyoungsang.moviebot.infra.telegram.bot;

import io.github.chunyoungsang.moviebot.domain.movie.crawler.MovieCrawler;
import io.github.chunyoungsang.moviebot.support.MySqlTestContainer;
import io.github.chunyoungsang.moviebot.infra.telegram.client.TelegramClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/** 스프링이 BotCommand 빈을 모아 @Order 순서대로 "/" 메뉴를 등록하는지 */
@SpringBootTest(properties = {"telegram.bot.token=test-token", "movie.check.cron=-"})
@DisplayName("봇 명령 메뉴 등록")
class BotCommandMenuTest {

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlTestContainer.registerFreshDatabase(registry);   // 테스트 클래스마다 독립된 DB
    }

    @MockitoBean private TelegramClient telegramClient;
    @MockitoBean private MovieCrawler crawler;

    @Test
    @DisplayName("앱 시작 시 등록된 명령어 전부를 정해진 순서로 메뉴에 등록한다")
    @SuppressWarnings("unchecked")
    void registersAllCommandsInOrder() {
        ArgumentCaptor<Map<String, String>> menu = ArgumentCaptor.forClass(Map.class);
        verify(telegramClient).setMyCommands(menu.capture());

        assertThat(menu.getValue().keySet())
                .containsExactly("start", "add", "list", "history", "cancel", "stop", "help");
        assertThat(menu.getValue()).containsEntry("add", "영화 알림 신청 · 영화제목 날짜 [지점] [관]");
    }
}
