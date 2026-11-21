package io.github.chunyoungsang.moviebot.notification.outbox;

public enum OutboxStatus {
    /** 발송 대기 (nextAttemptAt 이후 발송 가능) */
    PENDING,
    /** 리스너 또는 relay 가 선점해 발송 중 */
    SENDING,
    /** 발송 완료 */
    SENT,
    /** 재시도 포기 (최대 횟수 초과 또는 영구 실패) */
    DEAD
}
