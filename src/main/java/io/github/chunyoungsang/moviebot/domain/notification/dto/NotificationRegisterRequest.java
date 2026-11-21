package io.github.chunyoungsang.moviebot.domain.notification.dto;

import java.time.LocalDate;

/**
 * branchName, screenTypeName 은 optional — null이면 전체 대상.
 */
public record NotificationRegisterRequest(
        String chatId,
        String movieTitle,
        String brand,          // CGV (optional, 기본 CGV)
        String branchName,     // 영등포 (optional)
        String screenTypeName, // IMAX (optional)
        LocalDate targetDate
) {}