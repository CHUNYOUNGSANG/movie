package io.github.chunyoungsang.moviebot.domain.notification.dto;

import java.time.LocalDate;

/**
 * 크롤링 한 번의 단위: 영화 | 지점 | 날짜. 같은 조건의 신청은 몇 건이든 한 번만 크롤링한다.
 * 평소 주기(예매가 아직 안 열림)에는 신청 엔티티를 읽지 않고 이 값만 조회한다.
 *
 * @param branchName 지점명. null 이면 "전체 지점" 신청
 */
public record PendingGroup(String movieId, String movieTitle, String branchName, LocalDate targetDate) {
}
