package io.github.chunyoungsang.moviebot.movie.service;

import io.github.chunyoungsang.moviebot.movie.dto.ScreeningResult;
import io.github.chunyoungsang.moviebot.movie.entity.Movie;
import io.github.chunyoungsang.moviebot.movie.entity.ScreenType;
import io.github.chunyoungsang.moviebot.movie.entity.Theater;

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
}
