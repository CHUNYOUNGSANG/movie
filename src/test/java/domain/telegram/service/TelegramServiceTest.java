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

    @Test
    @DisplayName("deliver() 는 4xx(봇 차단 등)를 재시도 불가로, 5xx/429 를 재시도 가능으로 구분한다")
    void deliver_classifiesRetryable() {
        given(restTemplate.postForObject(anyString(), any(Map.class), eq(Map.class)))
                .willThrow(org.springframework.web.client.HttpClientErrorException.create(
                        org.springframework.http.HttpStatus.FORBIDDEN, "Forbidden", null, null, null))
                .willThrow(org.springframework.web.client.HttpClientErrorException.create(
                        org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", null, null, null))
                .willThrow(org.springframework.web.client.HttpServerErrorException.create(
                        org.springframework.http.HttpStatus.BAD_GATEWAY, "Bad Gateway", null, null, null));

        org.assertj.core.api.Assertions.assertThat(deliverError()).extracting(MessageSendException::isRetryable).isEqualTo(false);
        org.assertj.core.api.Assertions.assertThat(deliverError()).extracting(MessageSendException::isRetryable).isEqualTo(true);
        org.assertj.core.api.Assertions.assertThat(deliverError()).extracting(MessageSendException::isRetryable).isEqualTo(true);
    }

    @Test
    @DisplayName("deliver() 실패 예외에는 봇 토큰이 포함되지 않는다 (DB last_error / 로그 노출 방지)")
    void deliver_doesNotLeakBotToken() {
        // RestTemplate 의 I/O 예외 메시지에는 요청 URL(토큰 포함)이 그대로 들어간다
        given(restTemplate.postForObject(anyString(), any(Map.class), eq(Map.class)))
                .willThrow(new org.springframework.web.client.ResourceAccessException(
                        "I/O error on POST request for \"https://api.telegram.org/botreal-token-12345/sendMessage\": timeout"));

        MessageSendException e = deliverError();

        org.assertj.core.api.Assertions.assertThat(e.getMessage()).doesNotContain("real-token-12345");
        org.assertj.core.api.Assertions.assertThat(e.getCause()).isNull();
    }

    private MessageSendException deliverError() {
        try {
            telegramService.deliver("111", "알림");
        } catch (MessageSendException e) {
            return e;
        }
        throw new AssertionError("MessageSendException 이 발생해야 함");
    }
}
