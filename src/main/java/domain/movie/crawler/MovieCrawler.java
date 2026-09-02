package domain.movie.crawler;

import domain.movie.dto.ScreeningResult;

import java.time.LocalDate;
import java.util.List;

/**
 * 영화관 크롤러 인터페이스.
 *
 * OCP : 새 영화관은 이 인터페이스 구현체만 추가 — 기존 코드 수정 없음.
 * DIP : MovieServiceImpl은 이 추상화에만 의존하고 CGV/Megabox를 직접 알지 못함.
 * LSP : 모든 구현체는 실패 시 빈 리스트 반환, 예외 전파 금지.
 */
public interface MovieCrawler {

    String getCinemaName();

    List<ScreeningResult> crawl(String movieTitle, LocalDate date);
}