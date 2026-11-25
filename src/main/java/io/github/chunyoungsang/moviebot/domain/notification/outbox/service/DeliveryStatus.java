package io.github.chunyoungsang.moviebot.domain.notification.outbox.service;

import io.github.chunyoungsang.moviebot.domain.notification.outbox.entity.OutboxStatus;

/**
 * outbox 밖(봇, 컨트롤러)에 공개하는 발송 상태.
 * 선점(SENDING) 같은 Outbox 내부 사정은 감추고, 사용자에게 의미 있는 세 가지로만 나눈다.
 */
public enum DeliveryStatus {
    /** 발송 완료 */
    DELIVERED,
    /** 발송 대기 중이거나 발송 중 (재시도 대기 포함) */
    IN_PROGRESS,
    /** 재시도를 포기함 */
    FAILED,
    /** 보내기 전에 구독을 해제해서 보내지 않음 */
    CANCELED;

    static DeliveryStatus of(OutboxStatus status) {
        return switch (status) {
            case SENT -> DELIVERED;
            case PENDING, SENDING -> IN_PROGRESS;
            case DEAD -> FAILED;
            case CANCELED -> CANCELED;
        };
    }
}
