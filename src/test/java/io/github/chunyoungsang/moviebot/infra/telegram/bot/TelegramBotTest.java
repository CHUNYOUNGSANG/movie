package io.github.chunyoungsang.moviebot.infra.telegram.bot;

import io.github.chunyoungsang.moviebot.domain.movie.entity.Movie;
import io.github.chunyoungsang.moviebot.domain.notification.entity.NotificationSubscription;
import io.github.chunyoungsang.moviebot.domain.notification.outbox.OutboxStatus;
import io.github.chunyoungsang.moviebot.domain.notification.service.NotificationService;
import io.github.chunyoungsang.moviebot.domain.user.entity.User;
import io.github.chunyoungsang.moviebot.domain.user.service.UserService;
import io.github.chunyoungsang.moviebot.infra.telegram.bot.command.*;
import io.github.chunyoungsang.moviebot.infra.telegram.client.TelegramClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@DisplayName("텔레그램 봇 (폴링 → 명령 분배 → 명령어)")
class TelegramBotTest {

    @Mock private UserService userService;
    @Mock private NotificationService notificationService;
    @Mock private TelegramClient messageSender;
    @Mock private TelegramOffsetStore offsetStore;

    private BotCommandDispatcher service;
    private TelegramPoller poller;

    @BeforeEach
    void setUp() {
        // 운영과 같은 명령어 구성 (스프링이 BotCommand 빈을 모아 주입하는 것을 손으로 재현)
        service = new BotCommandDispatcher(List.of(
                new StartCommand(userService, messageSender),
                new AddCommand(notificationService, messageSender),
                new ListCommand(userService, notificationService, messageSender),
                new HistoryCommand(userService, notificationService, messageSender),
                new CancelCommand(notificationService, messageSender),
                new StopCommand(userService, messageSender),
                new HelpCommand(messageSender)));
        poller = new TelegramPoller(messageSender, offsetStore, service);
    }

    @Nested
    @DisplayName("poll()")
    class Poll {

        @Test
        @DisplayName("DB 에 저장된 offset 으로 getUpdates 를 요청하고, 처리한 update 다음 offset 을 저장한다")
        void usesSharedOffset() {
            given(offsetStore.get()).willReturn(101L);
            given(messageSender.getUpdates(101L)).willReturn(List.of(Map.of("update_id", 101)));

            poller.poll();

            then(offsetStore).should().save(102L);
        }

        @Test
        @DisplayName("한 명령의 처리가 실패해도 같은 배치의 다음 명령은 처리한다")
        void continuesAfterFailedUpdate() {
            given(offsetStore.get()).willReturn(1L);
            given(messageSender.getUpdates(1L)).willReturn(List.of(
                    Map.of("update_id", 1, "message", Map.of("chat", Map.of("id", "111"), "text", "/start")),
                    Map.of("update_id", 2, "message", Map.of("chat", Map.of("id", "222"), "text", "/help"))));
            given(userService.activate(any(), any())).willThrow(new IllegalStateException("DB 장애 (테스트)"));

            poller.poll();

            then(offsetStore).should().save(3L);
            then(messageSender).should().sendMessage(eq("222"), contains("사용법"));
        }
    }

    @Nested
    @DisplayName("/start")
    class Start {

        @Test
        @DisplayName("신규 사용자면 구독 완료 메시지를 보낸다")
        void newUser_sendsWelcomeMessage() {
            given(userService.activate("111", "chun")).willReturn(new UserService.ActivationResult(
                    User.builder().chatId("111").build(), UserService.ActivationResult.Status.CREATED));

            service.processUpdate(update("111", "chun", "/start"));

            then(messageSender).should().sendMessage(eq("111"), contains("구독 완료"));
        }

        @Test
        @DisplayName("이미 구독 중이면 안내 메시지를 보낸다")
        void alreadyActive_sendsInfoMessage() {
            given(userService.activate("111", "chun")).willReturn(new UserService.ActivationResult(
                    User.builder().chatId("111").build(), UserService.ActivationResult.Status.ALREADY_ACTIVE));

            service.processUpdate(update("111", "chun", "/start"));

            then(messageSender).should().sendMessage(eq("111"), contains("이미 구독 중"));
        }
    }

    @Nested
    @DisplayName("/stop")
    class Stop {

        @Test
        @DisplayName("구독 중이 아니면 안내 메시지를 보낸다")
        void notSubscribed_sendsInfoMessage() {
            given(userService.deactivate("111")).willReturn(Optional.empty());

            service.processUpdate(update("111", "chun", "/stop"));

            then(messageSender).should().sendMessage(eq("111"), contains("구독 중이 아닙니다"));
        }

        @Test
        @DisplayName("구독 중이면 해제하고 확인 메시지를 보낸다")
        void subscribed_deactivatesAndConfirms() {
            given(userService.deactivate("111"))
                    .willReturn(Optional.of(User.builder().chatId("111").active(false).build()));

            service.processUpdate(update("111", "chun", "/stop"));

            then(messageSender).should().sendMessage(eq("111"), contains("구독이 해제됐습니다"));
        }
    }

    @Nested
    @DisplayName("/list, /history")
    class ListAndHistory {

        @Test
        @DisplayName("/list는 대기 중인 구독만 보여준다")
        void list_showsOnlyPending() {
            User user = User.builder().chatId("111").build();
            given(userService.findByChatId("111")).willReturn(Optional.of(user));
            given(notificationService.findByUser(user)).willReturn(List.of(
                    subscription(1L, "스파이더맨", false),
                    subscription(2L, "파묘", true)
            ));

            service.processUpdate(update("111", "chun", "/list"));

            then(messageSender).should().sendMessage(eq("111"),
                    argThat(text -> text.contains("스파이더맨") && !text.contains("파묘")));
        }

        @Test
        @DisplayName("/history는 완료된 구독만 보여준다")
        void history_showsOnlyTriggered() {
            User user = User.builder().chatId("111").build();
            given(userService.findByChatId("111")).willReturn(Optional.of(user));
            given(notificationService.findByUser(user)).willReturn(List.of(
                    subscription(1L, "스파이더맨", false),
                    subscription(2L, "파묘", true)
            ));

            service.processUpdate(update("111", "chun", "/history"));

            then(messageSender).should().sendMessage(eq("111"),
                    argThat(text -> text.contains("파묘") && !text.contains("스파이더맨")));
        }

        @Test
        @DisplayName("/history는 발송 상태를 함께 보여주고, 실패 건이 있을 때만 안내 문구를 붙인다")
        void history_showsDeliveryStatus() {
            User user = User.builder().chatId("111").build();
            given(userService.findByChatId("111")).willReturn(Optional.of(user));
            given(notificationService.findByUser(user)).willReturn(List.of(
                    subscription(1L, "스파이더맨", true),
                    subscription(2L, "파묘", true),
                    subscription(3L, "위키드", true)
            ));
            given(notificationService.findDeliveryStatuses(List.of(1L, 2L, 3L))).willReturn(Map.of(
                    1L, OutboxStatus.SENT, 2L, OutboxStatus.PENDING, 3L, OutboxStatus.DEAD));

            service.processUpdate(update("111", "chun", "/history"));

            then(messageSender).should().sendMessage(eq("111"), argThat(text ->
                    text.contains("✅ #1 스파이더맨")
                    && text.contains("⏳ 발송 중 #2 파묘")
                    && text.contains("❌ 발송 실패 #3 위키드")
                    && text.contains("직접 확인해주세요")));
        }

        @Test
        @DisplayName("/history에 실패 건이 없으면 실패 안내 문구를 붙이지 않는다")
        void history_noFailureHintWhenAllSent() {
            User user = User.builder().chatId("111").build();
            given(userService.findByChatId("111")).willReturn(Optional.of(user));
            given(notificationService.findByUser(user)).willReturn(List.of(subscription(1L, "스파이더맨", true)));
            given(notificationService.findDeliveryStatuses(List.of(1L))).willReturn(Map.of(1L, OutboxStatus.SENT));

            service.processUpdate(update("111", "chun", "/history"));

            then(messageSender).should().sendMessage(eq("111"), argThat(text ->
                    text.contains("✅ #1 스파이더맨") && !text.contains("발송 실패")));
        }

        @Test
        @DisplayName("구독 자체가 없으면 /start 안내를 보낸다")
        void notSubscribed_sendsStartHint() {
            given(userService.findByChatId("111")).willReturn(Optional.empty());

            service.processUpdate(update("111", "chun", "/list"));

            then(messageSender).should().sendMessage(eq("111"), contains("/start"));
        }
    }

    @Nested
    @DisplayName("/add")
    class Add {

        @Test
        @DisplayName("날짜 형식이 잘못되면 register를 호출하지 않고 에러 메시지를 보낸다")
        void invalidDate_sendsFormatError() {
            service.processUpdate(update("111", "chun", "/add 스파이더맨 abcd"));

            then(notificationService).should(never()).register(any());
            then(messageSender).should().sendMessage(eq("111"), contains("날짜 형식"));
        }

        @Test
        @DisplayName("정상 입력이면 register를 호출하고 완료 메시지를 보낸다")
        void validInput_registersAndConfirms() {
            service.processUpdate(update("111", "chun", "/add 스파이더맨 2026-09-04"));

            then(notificationService).should().register(any());
            then(messageSender).should().sendMessage(eq("111"), contains("알림 신청 완료"));
        }
    }

    @Nested
    @DisplayName("/cancel")
    class Cancel {

        @Test
        @DisplayName("번호가 숫자가 아니면 형식 에러 메시지를 보낸다")
        void nonNumeric_sendsFormatError() {
            service.processUpdate(update("111", "chun", "/cancel abc"));

            then(messageSender).should().sendMessage(eq("111"), contains("번호가 올바르지"));
        }

        @Test
        @DisplayName("본인 소유가 아니면 SecurityException 메시지를 그대로 보여준다")
        void notOwner_sendsForbiddenMessage() {
            willThrow(new SecurityException("본인이 신청한 알림만 취소할 수 있습니다."))
                    .given(notificationService).cancel(1L, "111");

            service.processUpdate(update("111", "chun", "/cancel 1"));

            then(messageSender).should().sendMessage(eq("111"), contains("본인이 신청한"));
        }

        @Test
        @DisplayName("정상 취소면 확인 메시지를 보낸다")
        void success_sendsConfirmation() {
            service.processUpdate(update("111", "chun", "/cancel 1"));

            then(notificationService).should().cancel(1L, "111");
            then(messageSender).should().sendMessage(eq("111"), contains("취소됐습니다"));
        }
    }

    // ── helpers ───────────────────────────────────────────────

    private Map<String, Object> update(String chatId, String username, String text) {
        Map<String, Object> chat = new HashMap<>();
        chat.put("id", chatId);
        chat.put("username", username);

        Map<String, Object> message = new HashMap<>();
        message.put("chat", chat);
        message.put("text", text);

        Map<String, Object> update = new HashMap<>();
        update.put("message", message);
        return update;
    }

    private NotificationSubscription subscription(Long id, String title, boolean triggered) {
        return NotificationSubscription.builder()
                .subscriptionId(id)
                .movie(Movie.builder().movieId(title).title(title).build())
                .targetDate(LocalDate.of(2026, 9, 4))
                .triggered(triggered)
                .build();
    }
}
