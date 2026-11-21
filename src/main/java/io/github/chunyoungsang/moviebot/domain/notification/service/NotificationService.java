package io.github.chunyoungsang.moviebot.domain.notification.service;

import io.github.chunyoungsang.moviebot.domain.notification.dto.NotificationRegisterRequest;
import io.github.chunyoungsang.moviebot.domain.notification.entity.NotificationSubscription;
import io.github.chunyoungsang.moviebot.domain.user.entity.User;

import io.github.chunyoungsang.moviebot.domain.notification.outbox.OutboxStatus;

import java.util.List;
import java.util.Map;

public interface NotificationService {
    NotificationSubscription register(NotificationRegisterRequest request);
    List<NotificationSubscription> findAll();
    List<NotificationSubscription> findByUser(User user);
    /** 구독 ID → 최신 발송 상태 (Outbox 기준) */
    Map<Long, OutboxStatus> findDeliveryStatuses(List<Long> subscriptionIds);
    void cancel(Long subscriptionId, String chatId);
}