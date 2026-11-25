package io.github.chunyoungsang.moviebot.domain.notification.outbox.entity;

public enum OutboxStatus {
    /** 발송 대기 (nextAttemptAt 이후 발송 가능) */
    PENDING,
    /** 리스너 또는 relay 가 선점해 발송 중 */
    SENDING,
    /** 발송 완료 */
    SENT,
    /** 재시도 포기 (최대 횟수 초과 또는 영구 실패) */
    DEAD,
    /** 보내기 전에 받는 사람이 구독을 해제(/stop)하거나 봇을 차단해서 보내지 않음. 실패가 아니다 */
    CANCELED;

    /** 끝난 상태 (더 이상 바뀌지 않음) */
    public boolean isFinished() {
        return this == SENT || this == DEAD || this == CANCELED;
    }
}
