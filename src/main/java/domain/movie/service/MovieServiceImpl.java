package domain.movie.service;

import domain.movie.dto.ScreeningResult;
import domain.movie.entity.Movie;
import domain.movie.entity.Schedule;
import domain.movie.entity.ScreenType;
import domain.movie.entity.Theater;
import domain.movie.repository.MovieRepository;
import domain.movie.repository.ScheduleRepository;
import domain.movie.repository.ScreenTypeRepository;
import domain.movie.repository.TheaterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class MovieServiceImpl implements MovieService {

    private final MovieRepository movieRepository;
    private final TheaterRepository theaterRepository;
    private final ScreenTypeRepository screenTypeRepository;
    private final ScheduleRepository scheduleRepository;

    @Override
    public Movie findOrCreateMovie(String title) {
        return movieRepository.findByTitle(title)
                .orElseGet(() -> movieRepository.save(
                        Movie.builder().movieId(title).title(title).build()));
    }

    @Override
    public Theater findOrCreateTheater(String brand, String branchName) {
        return theaterRepository.findByBrandAndBranchName(brand, branchName)
                .orElseGet(() -> theaterRepository.save(Theater.builder()
                        .theaterId(brand + "|" + branchName)
                        .brand(brand).branchName(branchName).build()));
    }

    @Override
    public Theater findOrCreateTheaterFromResult(ScreeningResult result) {
        String siteNm = result.theater();  // "CGV 영등포타임스퀘어 SCREENX관..."
        String branchName = siteNm.replace("CGV ", "").split(" ")[0]; // "영등포타임스퀘어"
        String siteNo = extractSiteNo(result.bookingUrl());

        return theaterRepository.findByBrandAndBranchName("CGV", branchName)
                .map(t -> {
                    if (t.getSiteNo() == null && siteNo != null) {
                        t.setSiteNo(siteNo);
                    }
                    return t;
                })
                .orElseGet(() -> theaterRepository.save(Theater.builder()
                        .theaterId("CGV|" + branchName)
                        .brand("CGV").branchName(branchName).siteNo(siteNo).build()));
    }

    @Override
    public ScreenType findOrCreateScreenType(String typeName) {
        return screenTypeRepository.findByTypeName(typeName)
                .orElseGet(() -> screenTypeRepository.save(
                        ScreenType.builder().typeName(typeName).build()));
    }

    @Override
    public void saveOrUpdateSchedule(Movie movie, Theater theater, ScreenType screenType, ScreeningResult result) {
        scheduleRepository.findByMovieAndTheaterAndScreenTypeAndShowDateAndShowTime(
                        movie, theater, screenType, result.date(), result.time())
                .ifPresentOrElse(
                        s -> s.setOpened(result.bookingOpen()),
                        () -> scheduleRepository.save(Schedule.builder()
                                .movie(movie).theater(theater).screenType(screenType)
                                .showDate(result.date()).showTime(result.time())
                                .opened(result.bookingOpen()).build()));
    }

    private String extractSiteNo(String bookingUrl) {
        if (bookingUrl == null) return null;
        try {
            int idx = bookingUrl.indexOf("siteNo=");
            if (idx < 0) return null;
            String after = bookingUrl.substring(idx + 7);
            int end = after.indexOf('&');
            return end < 0 ? after : after.substring(0, end);
        } catch (Exception e) {
            return null;
        }
    }
}
