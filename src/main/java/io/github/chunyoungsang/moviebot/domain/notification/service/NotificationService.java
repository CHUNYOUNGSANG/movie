package io.github.chunyoungsang.moviebot.domain.notification.service;

import io.github.chunyoungsang.moviebot.domain.notification.dto.NotificationRegisterRequest;
import io.github.chunyoungsang.moviebot.domain.notification.entity.NotificationSubscription;
import io.github.chunyoungsang.moviebot.domain.user.entity.User;

import io.github.chunyoungsang.moviebot.domain.notification.outbox.service.DeliveryStatus;

import java.util.List;
import java.util.Map;

public interface NotificationService {
    NotificationSubscription register(NotificationRegisterRequest request);
    /** 해당 사용자의 알림 신청 목록 (가입하지 않은 사용자면 빈 목록) */
    List<NotificationSubscription> findByChatId(String chatId);
    List<NotificationSubscription> findByUser(User user);
    /** 구독 ID → 최신 발송 상태 (Outbox 기준) */
    Map<Long, DeliveryStatus> findDeliveryStatuses(List<Long> subscriptionIds);
    void cancel(Long subscriptionId, String chatId);
}