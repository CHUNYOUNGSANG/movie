package domain.movie.service;

import domain.movie.dto.MovieRegisterRequest;
import domain.movie.entity.TargetMovie;

import java.util.List;

/**
 * ISP : 호출자는 필요한 메서드만 사용.
 * DIP : Scheduler/Controller는 이 인터페이스에만 의존.
 */
public interface MovieService {

    TargetMovie register(MovieRegisterRequest request);

    void checkAndNotify();

    List<TargetMovie> findAll();

    void deactivate(Long id);
}