package domain.movie.service;

import domain.movie.dto.ScreeningResult;
import domain.movie.entity.Movie;
import domain.movie.entity.ScreenType;
import domain.movie.entity.Theater;

/**
 * SRP: 영화/극장/상영관/스케줄의 조회·생성 책임을 이 서비스로 모음.
 * 다른 도메인(Notification 등)은 Movie 관련 Repository를 직접 알지 못하고 이 인터페이스만 의존.
 */
public interface MovieService {

    Movie findOrCreateMovie(String title);

    Theater findOrCreateTheater(String brand, String branchName);

    // 크롤링 결과(siteNm 포함)로부터 Theater를 찾거나 생성. siteNo는 결과의 bookingUrl에서 파싱
    Theater findOrCreateTheaterFromResult(ScreeningResult result);

    ScreenType findOrCreateScreenType(String typeName);

    void saveOrUpdateSchedule(Movie movie, Theater theater, ScreenType screenType, ScreeningResult result);
}
