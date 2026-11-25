package io.github.chunyoungsang.moviebot.domain.notification.service;

import io.github.chunyoungsang.moviebot.domain.movie.dto.ScreeningResult;
import io.github.chunyoungsang.moviebot.domain.notification.dto.PendingGroup;
import io.github.chunyoungsang.moviebot.domain.notification.entity.NotificationSubscription;
import io.github.chunyoungsang.moviebot.domain.notification.outbox.service.OutboxService;
import io.github.chunyoungsang.moviebot.domain.notification.repository.NotificationSubscriptionRepository;
import io.github.chunyoungsang.moviebot.domain.notification.sender.AlertMessageFormatter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * 알림 구독의 상태 변경만 담당하는 짧은 트랜잭션 모음.
 * 크롤링 같은 외부 I/O 는 이 클래스 밖(트랜잭션 밖)에서 수행한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class NotificationStateService {

    private final NotificationSubscriptionRepository subscriptionRepository;
    private final OutboxService outboxService;
    private final AlertMessageFormatter alertMessageFormatter;
    private final Clock clock;

    /**
     * 크롤링 대상 그룹 조회 (미발송, 구독 중인 사용자, 상영일이 오늘 이후).
     * 신청 엔티티가 아니라 영화|지점|날짜 조합만 읽는다: 대기 신청이 수만 건이어도 메모리에는 그룹 수만큼만 올라온다.
     * 인터페이스에 선언한 쿼리 메서드는 Spring Data 가 트랜잭션을 걸지 않으므로,
     * 트랜잭션 밖에서 직접 호출하면 EntityManager 가 호출자 범위가 끝날 때까지 커넥션을 쥐고 있게 된다.
     * 명시적인 읽기 전용 트랜잭션으로 감싸 조회 직후 커넥션을 반납한다.
     */
    @Transactional(readOnly = true)
    public List<PendingGroup> findPendingGroups() {
        return subscriptionRepository.findPendingGroups(LocalDate.now(clock));
    }

    /** 예매가 열린 그룹만 그 그룹의 대기 중인 신청을 읽어 매칭한다 */
    public void markMatched(PendingGroup group, List<ScreeningResult> results) {
        if (results.isEmpty()) return;
        markMatched(subscriptionRepository.findPendingIdsInGroup(
                group.movieId(), group.branchName(), group.targetDate()), results);
    }

    /**
     * 크롤링 결과와 매칭되는 구독을 발송 처리한다.
     * 엔티티의 triggered 값(읽은 시점의 스냅샷)이 아니라 조건부 UPDATE 결과로 선점 여부를 판단한다.
     * → 다른 서버가 같은 구독을 동시에 처리해도 한 곳에서만 Outbox 가 생성된다.
     */
    public void markMatched(List<Long> subscriptionIds, List<ScreeningResult> results) {
        if (results.isEmpty()) return;

        for (NotificationSubscription sub : subscriptionRepository.findAllBySubscriptionIdIn(subscriptionIds)) {
            if (sub.isTriggered()) continue;
            if (!sub.getUser().isActive()) continue;   // 크롤링하는 동안 /stop 한 사용자

            List<ScreeningResult> matched = results.stream().filter(sub::matches).toList();
            if (matched.isEmpty()) continue;

            // 선점 실패 = 다른 서버(또는 다른 주기)가 이미 처리함
            if (subscriptionRepository.claimTriggered(sub.getSubscriptionId()) == 0) continue;

            // 구독 상태 변경과 "보낼 메시지" 기록을 같은 트랜잭션으로 커밋 (Transactional Outbox, 커밋 후 즉시 발송)
            outboxService.enqueue(sub, alertMessageFormatter.format(matched));
        }
    }
}
