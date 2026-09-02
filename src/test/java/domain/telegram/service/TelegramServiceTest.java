package domain.telegram.service;

import domain.movie.dto.ScreeningResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@DisplayName("TelegramService")
class TelegramServiceTest {

    @Mock private RestTemplate restTemplate;
    @InjectMocks private TelegramService telegramService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(telegramService, "botToken", "real-token-12345");
    }

    @Test
    @DisplayName("토큰 미설정 시 RestTemplate 을 호출하지 않는다")
    void doNotSend_whenTokenNotConfigured() {
        ReflectionTestUtils.setField(telegramService, "botToken", "YOUR_BOT_TOKEN_HERE");

        telegramService.sendMessage("123456", "테스트 메시지");

        then(restTemplate).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("유효 토큰이면 텔레그램 API 를 호출한다")
    void callsTelegramApi_whenTokenIsValid() {
        telegramService.sendMessage("123456789", "테스트 메시지");

        then(restTemplate).should().postForObject(
                contains("real-token-12345"), any(Map.class), eq(Map.class));
    }

    @Test
    @DisplayName("sendAlert() 는 ScreeningResult 정보로 API 를 호출한다")
    void sendAlert_callsApiWithResult() {
        ScreeningResult result = new ScreeningResult(
                "CGV", "강남", "파묘",
                LocalDate.of(2026, 9, 10), LocalTime.of(14, 30),
                50, true, "https://cgv.co.kr");

        telegramService.sendAlert("111", result);

        then(restTemplate).should().postForObject(anyString(), any(Map.class), eq(Map.class));
    }
}
