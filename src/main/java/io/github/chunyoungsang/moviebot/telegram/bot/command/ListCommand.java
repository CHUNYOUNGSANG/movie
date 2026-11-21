package io.github.chunyoungsang.moviebot.telegram.bot.command;

import io.github.chunyoungsang.moviebot.notification.entity.NotificationSubscription;
import io.github.chunyoungsang.moviebot.notification.service.NotificationService;
import io.github.chunyoungsang.moviebot.telegram.client.TelegramClient;
import io.github.chunyoungsang.moviebot.user.service.UserService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/** /list — 대기 중인(아직 예매가 열리지 않은) 알림 목록 */
@Order(3)
@Component
public class ListCommand extends SubscriptionListCommand {

    public ListCommand(UserService userService, NotificationService notificationService, TelegramClient telegramClient) {
        super(userService, notificationService, telegramClient);
    }

    @Override public String name() { return "list"; }
    @Override public String description() { return "대기 중인 알림 목록"; }

    @Override
    protected boolean includes(NotificationSubscription subscription) {
        return !subscription.isTriggered();
    }

    @Override
    protected String emptyMessage() {
        return "대기 중인 알림이 없습니다.\n/add 명령어로 등록해보세요!\n완료된 알림 보기: /history";
    }

    @Override
    protected String render(List<NotificationSubscription> subscriptions) {
        return "📋 <b>대기 중인 알림</b>\n\n"
                + lines(subscriptions, sub -> "")
                + "\n취소하려면: /cancel [번호]\n완료된 알림 보기: /history";
    }
}
