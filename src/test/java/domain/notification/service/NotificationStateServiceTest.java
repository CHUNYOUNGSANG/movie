package domain.notification.service;

import domain.movie.dto.ScreeningResult;
import domain.movie.entity.Movie;
import domain.movie.entity.Theater;
import domain.notification.entity.NotificationSubscription;
import domain.notification.repository.NotificationHistoryRepository;
import domain.notification.repository.NotificationSubscriptionRepository;
import domain.telegram.service.MessageSender;
import domain.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationStateService")
class NotificationStateServiceTest {

    @Mock private NotificationSubscriptionRepository subscriptionRepository;
    @Mock private NotificationHistoryRepository historyRepository;
    @Mock private MessageSender messageSender;

    private NotificationStateService service;

    @BeforeEach
    void setUp() {
        service = new NotificationStateService(subscriptionRepository, historyRepository, messageSender);
    }

    @Nested
    @DisplayName("markMatched()")
    class MarkMatched {

        @Test
        @DisplayName("매칭되는 상영이 있으면 triggered=true 로 바꾸고 알림을 발송한다")
        void notifiesOnMatch() {
            NotificationSubscription sub = subscription(1L, "123", null);
            given(subscriptionRepository.findAllBySubscriptionIdIn(List.of(1L))).willReturn(List.of(sub));

            service.markMatched(List.of(1L), List.of(screening("CGV 강남")));

            assertThat(sub.isTriggered()).isTrue();
            then(messageSender).should().sendAlerts(eq("123"), any());
        }

        @Test
        @DisplayName("지점 필터가 맞지 않으면 알림을 보내지 않는다")
        void skipsWhenTheaterNotMatched() {
            Theater hongdae = Theater.builder().theaterId("CGV|홍대").brand("CGV").branchName("홍대").build();
            NotificationSubscription sub = subscription(1L, "123", hongdae);
            given(subscriptionRepository.findAllBySubscriptionIdIn(List.of(1L))).willReturn(List.of(sub));

            service.markMatched(List.of(1L), List.of(screening("CGV 강남")));

            assertThat(sub.isTriggered()).isFalse();
            then(messageSender).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("다시 조회했을 때 이미 처리된 구독이면 건너뛴다")
        void skipsAlreadyTriggered() {
            NotificationSubscription sub = subscription(1L, "123", null);
            sub.setTriggered(true);
            given(subscriptionRepository.findAllBySubscriptionIdIn(List.of(1L))).willReturn(List.of(sub));

            service.markMatched(List.of(1L), List.of(screening("CGV 강남")));

            then(messageSender).shouldHaveNoInteractions();
        }
    }

    private NotificationSubscription subscription(Long id, String chatId, Theater theater) {
        return NotificationSubscription.builder()
                .subscriptionId(id)
                .user(User.builder().userId(1L).chatId(chatId).active(true).build())
                .movie(Movie.builder().movieId("스파이더맨").title("스파이더맨").build())
                .theater(theater)
                .targetDate(LocalDate.of(2026, 9, 4))
                .build();
    }

    private ScreeningResult screening(String theater) {
        return new ScreeningResult("CGV", theater, "스파이더맨",
                LocalDate.of(2026, 9, 4), LocalTime.of(14, 0),
                50, true, "https://cgv.co.kr");
    }
}
