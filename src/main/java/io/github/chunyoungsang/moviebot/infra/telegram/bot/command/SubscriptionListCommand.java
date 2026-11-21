package io.github.chunyoungsang.moviebot.infra.telegram.bot.command;

import io.github.chunyoungsang.moviebot.domain.notification.entity.NotificationSubscription;
import io.github.chunyoungsang.moviebot.domain.notification.service.NotificationService;
import io.github.chunyoungsang.moviebot.infra.telegram.bot.BotCommand;
import io.github.chunyoungsang.moviebot.infra.telegram.bot.BotMessage;
import io.github.chunyoungsang.moviebot.infra.telegram.client.TelegramClient;
import io.github.chunyoungsang.moviebot.domain.user.service.UserService;

import java.util.List;
import java.util.function.Function;

/**
 * 내 알림 목록을 보여주는 명령어의 공통 흐름 (템플릿 메서드).
 * 사용자 확인 → 조건에 맞는 구독 조회 → 비었으면 안내, 있으면 목록. 무엇을 고르고 어떻게 보여줄지만 하위 클래스가 정한다.
 */
abstract class SubscriptionListCommand implements BotCommand {

    protected final UserService userService;
    protected final NotificationService notificationService;
    protected final TelegramClient telegramClient;

    protected SubscriptionListCommand(UserService userService, NotificationService notificationService,
                                      TelegramClient telegramClient) {
        this.userService = userService;
        this.notificationService = notificationService;
        this.telegramClient = telegramClient;
    }

    /** 이 명령어가 보여줄 구독인지 */
    protected abstract boolean includes(NotificationSubscription subscription);

    protected abstract String emptyMessage();

    protected abstract String render(List<NotificationSubscription> subscriptions);

    @Override
    public final void handle(BotMessage message) {
        String chatId = message.chatId();
        userService.findByChatId(chatId).ifPresentOrElse(
                user -> {
                    List<NotificationSubscription> subscriptions = notificationService.findByUser(user).stream()
                            .filter(this::includes)
                            .toList();
                    telegramClient.sendMessage(chatId,
                            subscriptions.isEmpty() ? emptyMessage() : render(subscriptions));
                },
                () -> telegramClient.sendMessage(chatId, "/start 를 먼저 입력해주세요.")
        );
    }

    /** "#번호 영화 | 날짜 | 지점 | 관" 한 줄씩. prefix 는 줄 앞에 붙일 표시(발송 상태 등) */
    protected static String lines(List<NotificationSubscription> subscriptions,
                                  Function<NotificationSubscription, String> prefix) {
        StringBuilder sb = new StringBuilder();
        for (NotificationSubscription sub : subscriptions) {
            sb.append(prefix.apply(sub)).append(String.format(
                    "#%d %s | %s | %s | %s\n",
                    sub.getSubscriptionId(),
                    sub.getMovie().getTitle(),
                    sub.getTargetDate(),
                    sub.getTheater() != null ? sub.getTheater().getBranchName() : "전체 지점",
                    sub.getScreenType() != null ? sub.getScreenType().getTypeName() : "전체 관"
            ));
        }
        return sb.toString();
    }
}
