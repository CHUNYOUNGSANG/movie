package io.github.chunyoungsang.moviebot.movie.crawler;

/**
 * 크롤링 자체가 실패했음을 알린다 (브라우저 실행 실패, 사이트 차단, API 장애 등).
 * "상영 정보가 없음"은 실패가 아니므로 이 예외가 아니라 빈 리스트로 돌려준다.
 */
public class CrawlException extends RuntimeException {

    /** 실패 원인 분류 (지표 태그용, 값의 종류가 적어야 함): browser, http_403, http_502, request_failed, bad_response ... */
    private final String reason;

    public CrawlException(String reason, String message) {
        this(reason, message, null);
    }

    public CrawlException(String reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
