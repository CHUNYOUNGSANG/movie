package io.github.chunyoungsang.moviebot.domain.movie.service;

import io.github.chunyoungsang.moviebot.domain.movie.dto.ScreeningResult;
import io.github.chunyoungsang.moviebot.domain.movie.entity.Movie;
import io.github.chunyoungsang.moviebot.domain.movie.entity.ScreenType;
import io.github.chunyoungsang.moviebot.domain.movie.entity.Theater;

import java.time.LocalDate;
import java.util.List;

/**
 * SRP: 영화/극장/상영관/스케줄의 조회·생성 책임을 이 서비스로 모음.
 * 다른 도메인(Notification 등)은 Movie 관련 Repository를 직접 알지 못하고 이 인터페이스만 의존.
 */
public interface MovieService {

    Movie findOrCreateMovie(String title);

    Theater findOrCreateTheater(String brand, String branchName);

    ScreenType findOrCreateScreenType(String typeName);

    /**
     * 크롤링 결과 전체를 하나의 트랜잭션으로 Schedule 에 반영 (없으면 INSERT, 있으면 예매 오픈 여부 갱신).
     * 결과 건수와 무관하게 일정한 수의 쿼리로 처리한다.
     */
    void saveScreenings(List<ScreeningResult> results);

    /**
     * 상영일이 기준일 이전인 일정을 최대 limit 건 삭제한다 (호출마다 짧은 트랜잭션).
     * 일정은 크롤링 결과 upsert 에만 쓰이므로 상영일이 지나면 쓸 일이 없다.
     */
    int deleteSchedulesShownBefore(LocalDate cutoff, int limit);
}
