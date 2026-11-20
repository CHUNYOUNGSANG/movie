package domain.movie.crawler;

import domain.movie.dto.ScreeningResult;

import java.time.LocalDate;
import java.util.List;

/**
 * 영화관 크롤러 인터페이스.
 *
 * OCP : 새 영화관은 이 인터페이스 구현체만 추가 — 기존 코드 수정 없음.
 * DIP : MovieServiceImpl은 이 추상화에만 의존하고 CGV/Megabox를 직접 알지 못함.
 * LSP : 모든 구현체는 같은 규칙을 따른다.
 *       - 상영 정보가 없으면 빈 리스트
 *       - 조회 자체가 실패하면(브라우저 실행 실패, 사이트 차단, API 장애) CrawlException
 *       호출하는 쪽은 그룹 단위로 실패를 격리하므로 예외가 다른 그룹 처리를 막지 않는다.
 *       (예전에는 "실패 시 빈 리스트"였으나, 실패와 상영 없음을 구분할 수 없어 차단을 감지하지 못했다)
 */
public interface MovieCrawler {

    String getCinemaName();

    List<ScreeningResult> crawl(String movieTitle, LocalDate date);

    // siteHint: 영화관별 극장 식별자 (CGV=siteNo). null이면 전체 극장 조회 시도
    default List<ScreeningResult> crawl(String movieTitle, String siteHint, LocalDate date) {
        return crawl(movieTitle, date);
    }
}