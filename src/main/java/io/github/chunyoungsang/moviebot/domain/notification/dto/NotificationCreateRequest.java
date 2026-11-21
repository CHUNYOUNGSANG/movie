package io.github.chunyoungsang.moviebot.domain.notification.dto;

import java.time.LocalDate;

/**
 * API 알림 신청 요청. 신청자는 요청 본문이 아니라 인증된 사용자(토큰)로 정한다.
 * branchName, screenTypeName 은 optional — null 이면 전체 대상.
 */
public record NotificationCreateRequest(
        String movieTitle,
        String brand,          // CGV (optional, 기본 CGV)
        String branchName,     // 영등포 (optional)
        String screenTypeName, // IMAX (optional)
        LocalDate targetDate
) {

    public NotificationRegisterRequest forUser(String chatId) {
        return new NotificationRegisterRequest(chatId, movieTitle, brand, branchName, screenTypeName, targetDate);
    }
}
