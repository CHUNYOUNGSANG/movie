package io.github.chunyoungsang.moviebot.telegram.service;

import io.github.chunyoungsang.moviebot.movie.crawler.MovieCrawler;
import io.github.chunyoungsang.moviebot.movie.entity.Movie;
import io.github.chunyoungsang.moviebot.movie.repository.MovieRepository;
import io.github.chunyoungsang.moviebot.notification.entity.NotificationSubscription;
import io.github.chunyoungsang.moviebot.notification.outbox.NotificationOutbox;
import io.github.chunyoungsang.moviebot.notification.outbox.NotificationOutboxRepository;
import io.github.chunyoungsang.moviebot.notification.outbox.OutboxStatus;
import io.github.chunyoungsang.moviebot.notification.repository.NotificationHistoryRepository;
import io.github.chunyoungsang.moviebot.notification.repository.NotificationSubscriptionRepository;
import io.github.chunyoungsang.moviebot.user.entity.User;
import io.github.chunyoungsang.moviebot.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestTemplate;
import io.github.chunyoungsang.moviebot.support.MySqlTestContainer;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * 발송에 최종 실패(DEAD)한 알림이 /history 에서 "완료"로 보이는 문제 재현.
 * 사용자는 받지 못한 알림을 받은 것으로 오해하고 다시 신청하지 않는다.
 */
@SpringBootTest(properties = {
        "telegram.bot.token=test-token",
        "movie.check.cron=-",
        "notification.outbox.relay-delay-ms=600000"   // 테스트 중 relay 가 Outbox 상태를 바꾸지 않도록
})
@DisplayName("/history 발송 상태 표시 재현")
class HistoryDeliveryStatusTest {

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlTestContainer.registerFreshDatabase(registry);   // 테스트 클래스마다 독립된 DB
    }

    private static final String CHAT_ID = "5001";

    @MockitoBean private RestTemplate restTemplate;
    @MockitoBean private MovieCrawler crawler;
    @MockitoBean private TelegramService messageSender;   // 봇 답장을 가로채 확인

    @Autowired private TelegramPollingService pollingService;
    @Autowired private UserRepository userRepository;
    @Autowired private MovieRepository movieRepository;
    @Autowired private NotificationSubscriptionRepository subscriptionRepository;
    @Autowired private NotificationHistoryRepository historyRepository;
    @Autowired private NotificationOutboxRepository outboxRepository;

    @AfterEach
    void cleanUp() {
        outboxRepository.deleteAllInBatch();
        historyRepository.deleteAllInBatch();
        subscriptionRepository.deleteAllInBatch();
        movieRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("[DEAD 표시] 발송에 최종 실패한 알림은 /history 에서 완료가 아니라 발송 실패로 보여야 한다")
    void deadAlertIsShownAsFailed() {
        User user = userRepository.save(User.builder().chatId(CHAT_ID).username("u" + CHAT_ID).build());
        triggeredWithOutbox(user, "위키드", OutboxStatus.SENT);
        triggeredWithOutbox(user, "파묘", OutboxStatus.DEAD);

        pollingService.processUpdate(update("/history"));

        String sent = historyLine(captureReply(), "위키드");
        String dead = historyLine(captureReply(), "파묘");
        System.out.printf("[DEAD 표시] /history%n  %s%n  %s%n", sent, dead);
        assertThat(dead).as("DEAD 알림 줄").contains("발송 실패");
        assertThat(sent).as("SENT 알림 줄").doesNotContain("발송 실패");
    }

    // ── helpers ───────────────────────────────────────────────

    private void triggeredWithOutbox(User user, String title, OutboxStatus status) {
        Movie movie = movieRepository.save(Movie.builder().movieId(title).title(title).build());
        NotificationSubscription sub = NotificationSubscription.builder()
                .user(user).movie(movie).targetDate(LocalDate.of(2026, 12, 24)).build();
        sub.setTriggered(true);
        subscriptionRepository.save(sub);
        outboxRepository.save(NotificationOutbox.builder()
                .subscription(sub).chatId(user.getChatId()).message(title + " 예매 오픈")
                .status(status).nextAttemptAt(LocalDateTime.now())
                .build());
    }

    private String captureReply() {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(messageSender).sendMessage(eq(CHAT_ID), text.capture());
        return text.getValue();
    }

    private static String historyLine(String reply, String title) {
        return Arrays.stream(reply.split("\n"))
                .filter(line -> line.contains(title))
                .findFirst()
                .orElseThrow(() -> new AssertionError(title + " 줄이 없음:\n" + reply));
    }

    private static Map<String, Object> update(String text) {
        Map<String, Object> chat = new HashMap<>();
        chat.put("id", CHAT_ID);
        chat.put("username", "u" + CHAT_ID);
        Map<String, Object> message = new HashMap<>();
        message.put("chat", chat);
        message.put("text", text);
        Map<String, Object> update = new HashMap<>();
        update.put("message", message);
        return update;
    }
}
