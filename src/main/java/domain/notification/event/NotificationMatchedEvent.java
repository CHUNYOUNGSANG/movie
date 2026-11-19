package domain.notification.event;

import domain.movie.dto.ScreeningResult;

import java.util.List;

/**
 * 구독 조건에 맞는 상영이 발견되어 발송 대상이 된 시점에 발행되는 이벤트.
 * 트랜잭션 커밋 이후에만 발송되도록 NotificationSendListener 가 받는다.
 */
public record NotificationMatchedEvent(
        Long subscriptionId,
        String chatId,
        List<ScreeningResult> results
) {
}
