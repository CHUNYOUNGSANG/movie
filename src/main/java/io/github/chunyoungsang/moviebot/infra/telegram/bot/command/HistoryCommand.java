package io.github.chunyoungsang.moviebot.infra.telegram.bot.command;

import io.github.chunyoungsang.moviebot.domain.notification.entity.NotificationSubscription;
import io.github.chunyoungsang.moviebot.domain.notification.outbox.service.DeliveryStatus;
import io.github.chunyoungsang.moviebot.domain.notification.service.NotificationService;
import io.github.chunyoungsang.moviebot.infra.telegram.client.TelegramClient;
import io.github.chunyoungsang.moviebot.domain.user.service.UserService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** /history — 매칭된 알림과 실제 발송 결과(Outbox 상태). 발송에 실패한 알림을 완료로 보이지 않게 한다 */
@Order(4)
@Component
public class HistoryCommand extends SubscriptionListCommand {

    public HistoryCommand(UserService userService, NotificationService notificationService, TelegramClient telegramClient) {
        super(userService, notificationService, telegramClient);
    }

    @Override public String name() { return "history"; }
    @Override public String description() { return "완료된 알림 목록"; }

    @Override
    protected boolean includes(NotificationSubscription subscription) {
        return subscription.isTriggered();
    }

    @Override
    protected String emptyMessage() {
        return "완료된 알림이 없습니다.";
    }

    @Override
    protected String render(List<NotificationSubscription> subscriptions) {
        Map<Long, DeliveryStatus> statuses = notificationService.findDeliveryStatuses(
                subscriptions.stream().map(NotificationSubscription::getSubscriptionId).toList());
        boolean anyFailed = statuses.containsValue(DeliveryStatus.FAILED);
        return "📜 <b>알림 기록</b>\n\n"
                + lines(subscriptions, sub -> deliveryLabel(statuses.get(sub.getSubscriptionId())))
                + (anyFailed ? "\n❌ 발송 실패: 텔레그램 전송에 끝내 실패한 알림이에요. 예매 오픈 여부를 직접 확인해주세요." : "");
    }

    private static String deliveryLabel(DeliveryStatus status) {
        return switch (status) {
            case DELIVERED -> "✅ ";
            case IN_PROGRESS -> "⏳ 발송 중 ";
            case FAILED -> "❌ 발송 실패 ";
            case CANCELED -> "🔕 구독 해제로 미발송 ";
        };
    }
}
