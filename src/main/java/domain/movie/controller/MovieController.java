package domain.movie.controller;

import domain.movie.dto.MovieRegisterRequest;
import domain.movie.entity.TargetMovie;
import domain.movie.service.MovieService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 감시 영화 관리 REST API.
 *
 * POST   /api/movies          영화 등록
 * GET    /api/movies          전체 목록 조회
 * PATCH  /api/movies/{id}     비활성화 (감시 중단)
 */
@RestController
@RequestMapping("/api/movies")
@RequiredArgsConstructor
public class MovieController {

    private final MovieService movieService;   // 인터페이스에 의존 (DIP)

    @PostMapping
    public ResponseEntity<TargetMovie> register(@RequestBody MovieRegisterRequest request) {
        return ResponseEntity.ok(movieService.register(request));
    }

    @GetMapping
    public ResponseEntity<List<TargetMovie>> findAll() {
        return ResponseEntity.ok(movieService.findAll());
    }

    @PatchMapping("/{id}/deactivate")
    public ResponseEntity<Void> deactivate(@PathVariable Long id) {
        movieService.deactivate(id);
        return ResponseEntity.noContent().build();
    }
}