package domain.notification.service;

import domain.notification.dto.NotificationRegisterRequest;
import domain.notification.entity.NotificationRequest;

import java.util.List;

public interface NotificationService {
    NotificationRequest register(NotificationRegisterRequest request);
    List<NotificationRequest> findAll();
    void cancel(Long requestId);
    void checkAndNotify();
}