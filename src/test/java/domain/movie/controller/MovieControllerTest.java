package domain.movie.controller;

import domain.TestApplication;
import domain.movie.dto.MovieRegisterRequest;
import domain.movie.entity.TargetMovie;
import domain.movie.service.MovieService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = TestApplication.class)
@AutoConfigureMockMvc
@DisplayName("MovieController")
class MovieControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private MovieService movieService;

    @Test
    @DisplayName("POST /api/movies — 등록 성공 시 200과 저장된 영화를 반환한다")
    void register_returns200() throws Exception {
        MovieRegisterRequest request = new MovieRegisterRequest("파묘", "CGV", "영등포", LocalDate.of(2026, 9, 10));
        TargetMovie saved = TargetMovie.builder()
                .id(1L).title("파묘").cinema("CGV")
                .targetDate(LocalDate.of(2026, 9, 10))
                .active(true).createdAt(LocalDateTime.now()).build();

        given(movieService.register(any())).willReturn(saved);

        mockMvc.perform(post("/api/movies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.title").value("파묘"))
                .andExpect(jsonPath("$.cinema").value("CGV"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("GET /api/movies — 전체 목록을 200으로 반환한다")
    void findAll_returns200() throws Exception {
        List<TargetMovie> movies = List.of(
                TargetMovie.builder().id(1L).title("파묘").cinema("CGV").active(true).build(),
                TargetMovie.builder().id(2L).title("검은사제들").cinema("MEGABOX").active(false).build()
        );
        given(movieService.findAll()).willReturn(movies);

        mockMvc.perform(get("/api/movies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].title").value("파묘"))
                .andExpect(jsonPath("$[1].title").value("검은사제들"));
    }

    @Test
    @DisplayName("PATCH /api/movies/{id}/deactivate — 비활성화 성공 시 204를 반환한다")
    void deactivate_returns204() throws Exception {
        willDoNothing().given(movieService).deactivate(1L);

        mockMvc.perform(patch("/api/movies/1/deactivate"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("PATCH /api/movies/{id}/deactivate — 존재하지 않는 ID이면 400을 반환한다")
    void deactivate_returns400_whenNotFound() throws Exception {
        willThrow(new IllegalArgumentException("대상 영화를 찾을 수 없습니다: 999"))
                .given(movieService).deactivate(999L);

        mockMvc.perform(patch("/api/movies/999/deactivate"))
                .andExpect(status().isBadRequest());
    }
}