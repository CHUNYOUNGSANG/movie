package io.github.chunyoungsang.moviebot.domain.notification.service;

import io.github.chunyoungsang.moviebot.domain.movie.dto.ScreeningResult;
import io.github.chunyoungsang.moviebot.domain.movie.entity.Movie;
import io.github.chunyoungsang.moviebot.domain.movie.entity.Theater;
import io.github.chunyoungsang.moviebot.domain.notification.entity.NotificationSubscription;
import io.github.chunyoungsang.moviebot.domain.notification.outbox.service.OutboxService;
import io.github.chunyoungsang.moviebot.domain.notification.sender.AlertMessageFormatter;
import io.github.chunyoungsang.moviebot.domain.notification.repository.NotificationSubscriptionRepository;
import io.github.chunyoungsang.moviebot.domain.user.entity.User;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationStateService")
class NotificationStateServiceTest {

    @Mock private NotificationSubscriptionRepository subscriptionRepository;
    @Mock private OutboxService outboxService;
    @Mock private AlertMessageFormatter alertMessageFormatter;

    private NotificationStateService service;

    @BeforeEach
    void setUp() {
        service = new NotificationStateService(subscriptionRepository, outboxService, alertMessageFormatter);
    }

    @Nested
    @DisplayName("markMatched()")
    class MarkMatched {

        @Test
        @DisplayName("매칭되는 상영이 있으면 triggered=true 로 바꾸고 Outbox 에 기록한다")
        void notifiesOnMatch() {
            NotificationSubscription sub = subscription(1L, "123", null);
            given(subscriptionRepository.findAllBySubscriptionIdIn(List.of(1L))).willReturn(List.of(sub));

            given(subscriptionRepository.claimTriggered(1L)).willReturn(1);
            given(alertMessageFormatter.format(any())).willReturn("알림 본문");

            service.markMatched(List.of(1L), List.of(screening("CGV 강남")));

            then(subscriptionRepository).should().claimTriggered(1L);
            then(outboxService).should().enqueue(sub, "알림 본문");   // 발송 이벤트 발행은 OutboxService 가 함께 한다
        }

        @Test
        @DisplayName("지점 필터가 맞지 않으면 알림을 보내지 않는다")
        void skipsWhenTheaterNotMatched() {
            Theater hongdae = Theater.builder().theaterId("CGV|홍대").brand("CGV").branchName("홍대").build();
            NotificationSubscription sub = subscription(1L, "123", hongdae);
            given(subscriptionRepository.findAllBySubscriptionIdIn(List.of(1L))).willReturn(List.of(sub));

            service.markMatched(List.of(1L), List.of(screening("CGV 강남")));

            then(subscriptionRepository).should(never()).claimTriggered(any());
            then(outboxService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("다시 조회했을 때 이미 처리된 구독이면 건너뛴다")
        void skipsAlreadyTriggered() {
            NotificationSubscription sub = subscription(1L, "123", null);
            sub.setTriggered(true);
            given(subscriptionRepository.findAllBySubscriptionIdIn(List.of(1L))).willReturn(List.of(sub));

            service.markMatched(List.of(1L), List.of(screening("CGV 강남")));

            then(outboxService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("크롤링하는 동안 사용자가 구독을 해제(/stop)했으면 선점하지 않고 Outbox 도 만들지 않는다")
        void skipsWhenUserStoppedDuringCrawl() {
            NotificationSubscription sub = subscription(1L, "123", null);
            sub.getUser().setActive(false);
            given(subscriptionRepository.findAllBySubscriptionIdIn(List.of(1L))).willReturn(List.of(sub));

            service.markMatched(List.of(1L), List.of(screening("CGV 강남")));

            then(subscriptionRepository).should(never()).claimTriggered(any());
            then(outboxService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("선점(조건부 UPDATE)에 실패하면 다른 서버가 처리한 것이므로 Outbox 를 만들지 않는다")
        void skipsWhenClaimFails() {
            NotificationSubscription sub = subscription(1L, "123", null);
            given(subscriptionRepository.findAllBySubscriptionIdIn(List.of(1L))).willReturn(List.of(sub));
            given(subscriptionRepository.claimTriggered(1L)).willReturn(0);

            service.markMatched(List.of(1L), List.of(screening("CGV 강남")));

            then(outboxService).shouldHaveNoInteractions();
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
