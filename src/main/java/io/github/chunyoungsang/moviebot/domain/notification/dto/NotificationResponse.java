package io.github.chunyoungsang.moviebot.domain.notification.dto;

import io.github.chunyoungsang.moviebot.domain.notification.entity.NotificationSubscription;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * API 알림 응답. 엔티티를 그대로 내보내지 않는다.
 * - 사용자 정보(chatId, userId, username)는 싣지 않는다: 요청한 본인 것이므로 필요 없고, 노출되면 다른 API 의 식별자로 쓰일 수 있다
 * - CGV 내부 코드(movNo, siteNo)와 DB 키는 싣지 않는다
 * - 엔티티에 필드가 추가돼도 응답이 저절로 바뀌지 않는다
 */
public record NotificationResponse(
        Long subscriptionId,
        String movieTitle,
        String brand,          // null = 전체 지점
        String branchName,     // null = 전체 지점
        String screenType,     // null = 전체 관
        LocalDate targetDate,
        boolean triggered,
        boolean expired,       // 상영일이 지나도록 예매가 열리지 않아 종료됨
        LocalDateTime createdAt
) {

    public static NotificationResponse from(NotificationSubscription sub) {
        return new NotificationResponse(
                sub.getSubscriptionId(),
                sub.getMovie().getTitle(),
                sub.getTheater() == null ? null : sub.getTheater().getBrand(),
                sub.getTheater() == null ? null : sub.getTheater().getBranchName(),
                sub.getScreenType() == null ? null : sub.getScreenType().getTypeName(),
                sub.getTargetDate(),
                sub.isTriggered(),
                sub.isExpired(),
                sub.getCreatedAt());
    }
}
