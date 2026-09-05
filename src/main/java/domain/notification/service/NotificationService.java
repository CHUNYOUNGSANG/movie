package domain.notification.service;

import domain.notification.dto.NotificationRegisterRequest;
import domain.notification.entity.NotificationSubscription;
import domain.user.entity.User;

import java.util.List;

public interface NotificationService {
    NotificationSubscription register(NotificationRegisterRequest request);
    List<NotificationSubscription> findAll();
    List<NotificationSubscription> findByUser(User user);
    void cancel(Long subscriptionId, String chatId);
    void checkAndNotify();
}