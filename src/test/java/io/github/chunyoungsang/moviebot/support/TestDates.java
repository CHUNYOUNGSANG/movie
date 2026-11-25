package io.github.chunyoungsang.moviebot.support;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 테스트용 날짜. 지난 날짜 신청은 크롤링하지 않고 신청도 받지 않으므로,
 * 고정 날짜(예: 2026-12-24)를 쓰면 그 날이 지난 뒤 테스트가 깨진다. 실행 시점 기준 상대 날짜를 쓴다.
 */
public final class TestDates {

    public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private TestDates() {
    }

    public static LocalDate today() {
        return LocalDate.now(SEOUL);
    }

    /** 아직 오지 않은 상영일 (한 달 뒤) */
    public static LocalDate future() {
        return today().plusMonths(1);
    }
}
