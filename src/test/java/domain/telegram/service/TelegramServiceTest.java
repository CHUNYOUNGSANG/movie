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
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
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
    @DisplayName("formatAlerts() 는 영화/극장/시간 정보를 담은 메시지를 만든다")
    void formatAlerts_containsScreeningInfo() {
        ScreeningResult result = new ScreeningResult(
                "CGV", "강남", "파묘",
                LocalDate.of(2026, 9, 10), LocalTime.of(14, 30),
                50, true, "https://cgv.co.kr");

        String message = telegramService.formatAlerts(List.of(result));

        org.assertj.core.api.Assertions.assertThat(message).contains("파묘", "강남", "14:30");
    }

    @Test
    @DisplayName("deliver() 는 API 호출이 실패하면 MessageSendException 을 던진다")
    void deliver_throwsOnFailure() {        given(restTemplate.postForObject(anyString(), any(Map.class), eq(Map.class)))
                .willThrow(new org.springframework.web.client.RestClientException("장애"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> telegramService.deliver("111", "알림"))
                .isInstanceOf(MessageSendException.class);
    }

    @Test
    @DisplayName("sendMessage() 는 API 호출이 실패해도 예외를 던지지 않는다")
    void sendMessage_swallowsFailure() {
        given(restTemplate.postForObject(anyString(), any(Map.class), eq(Map.class)))
                .willThrow(new org.springframework.web.client.RestClientException("장애"));

        telegramService.sendMessage("111", "안내");
    }
}
