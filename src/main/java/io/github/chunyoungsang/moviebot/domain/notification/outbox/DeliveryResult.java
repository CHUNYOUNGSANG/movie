package io.github.chunyoungsang.moviebot.domain.notification.outbox;

/** 발송 시도 한 번의 결과 (지표 태그로 사용) */
public enum DeliveryResult {
    /** 발송 완료 */
    SENT,
    /** 일시 장애로 실패, 백오프 후 재시도 */
    RETRY,
    /** 텔레그램 속도 제한(429), retry_after 후 재시도 (시도 횟수 차감 없음) */
    RATE_LIMITED,
    /** 재시도 포기 (영구 실패 또는 최대 시도 횟수 초과) */
    DEAD;

    public String tag() {
        return name().toLowerCase();
    }
}
