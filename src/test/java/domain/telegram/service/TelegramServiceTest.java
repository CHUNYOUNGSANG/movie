package domain.telegram.service;

import domain.movie.dto.ScreeningResult;
import domain.telegram.entity.Subscriber;
import domain.telegram.repository.SubscriberRepository;
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
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@DisplayName("TelegramService")
class TelegramServiceTest {

    @Mock private RestTemplate restTemplate;
    @Mock private SubscriberRepository subscriberRepository;
    @InjectMocks private TelegramService telegramService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(telegramService, "botToken", "real-token-12345");
    }

    @Test
    @DisplayName("토큰이 미설정 상태이면 RestTemplate을 호출하지 않는다")
    void doNotSend_whenTokenNotConfigured() {
        ReflectionTestUtils.setField(telegramService, "botToken", "YOUR_BOT_TOKEN_HERE");

        telegramService.sendMessage("123456", "테스트 메시지");

        then(restTemplate).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("유효한 토큰이면 텔레그램 API를 호출한다")
    void callsTelegramApi_whenTokenIsValid() {
        telegramService.sendMessage("123456789", "테스트 메시지");

        then(restTemplate).should().postForObject(
                contains("real-token-12345"),
                any(Map.class),
                eq(Map.class)
        );
    }

    @Test
    @DisplayName("sendAlert()는 활성 구독자 전원에게 메시지를 발송한다")
    void sendAlert_broadcastsToAllActiveSubscribers() {
        Subscriber sub1 = Subscriber.builder().chatId("111").active(true).build();
        Subscriber sub2 = Subscriber.builder().chatId("222").active(true).build();
        given(subscriberRepository.findAllByActiveTrue()).willReturn(List.of(sub1, sub2));

        ScreeningResult result = new ScreeningResult(
                "CGV", "강남", "파묘",
                LocalDate.of(2026, 9, 10), LocalTime.of(14, 30),
                50, true, "https://cgv.co.kr"
        );

        telegramService.sendAlert(result);

        then(restTemplate).should(org.mockito.Mockito.times(2))
                .postForObject(anyString(), any(Map.class), eq(Map.class));
    }

    @Test
    @DisplayName("구독자가 없으면 RestTemplate을 호출하지 않는다")
    void sendAlert_skipsWhenNoSubscribers() {
        given(subscriberRepository.findAllByActiveTrue()).willReturn(List.of());

        telegramService.sendAlert(new ScreeningResult(
                "CGV", "강남", "파묘",
                LocalDate.now(), LocalTime.of(14, 0),
                50, true, "https://cgv.co.kr"
        ));

        then(restTemplate).should(never()).postForObject(any(), any(), any());
    }
}