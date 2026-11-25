package io.github.chunyoungsang.moviebot.domain.notification.repository;

import io.github.chunyoungsang.moviebot.domain.movie.entity.Movie;
import io.github.chunyoungsang.moviebot.domain.movie.entity.ScreenType;
import io.github.chunyoungsang.moviebot.domain.movie.entity.Theater;
import io.github.chunyoungsang.moviebot.domain.notification.entity.NotificationSubscription;
import io.github.chunyoungsang.moviebot.domain.user.entity.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface NotificationSubscriptionRepository extends JpaRepository<NotificationSubscription, Long> {

    /**
     * 크롤링 대상: 아직 매칭되지 않았고, 구독 중인(active) 사용자의 것이며, 상영일이 오늘 이후인 신청.
     * - /stop 하거나 봇을 차단한 사용자는 크롤링하지 않는다
     * - 상영일이 지난 신청은 예매가 열릴 일이 없으므로 크롤링하지 않는다
     */
    @EntityGraph(attributePaths = {"movie", "theater", "screenType", "user"})
    @Query("select s from NotificationSubscription s " +
           "where s.triggered = false and s.user.active = true and s.targetDate >= :today")
    List<NotificationSubscription> findPending(@Param("today") LocalDate today);

    @EntityGraph(attributePaths = {"movie", "theater", "screenType"})
    List<NotificationSubscription> findByUser(User user);

    /**
     * 같은 조건(영화·지점·상영관·날짜)의 대기 중인 신청이 있는지. 지점·상영관이 null(전체)이면 null 끼리 같은 조건으로 본다.
     * (파생 쿼리 대신 JPQL: null 인자를 "is null" 로 비교하려면 조건을 명시해야 한다)
     */
    @Query("select count(s) > 0 from NotificationSubscription s " +
           "where s.user = :user and s.movie = :movie and s.targetDate = :targetDate and s.triggered = false " +
           "and ((:theater is null and s.theater is null) or s.theater = :theater) " +
           "and ((:screenType is null and s.screenType is null) or s.screenType = :screenType)")
    boolean existsPendingDuplicate(@Param("user") User user,
                                   @Param("movie") Movie movie,
                                   @Param("theater") Theater theater,
                                   @Param("screenType") ScreenType screenType,
                                   @Param("targetDate") LocalDate targetDate);

    @EntityGraph(attributePaths = {"movie", "theater", "screenType", "user"})
    Optional<NotificationSubscription> findById(Long id);

    @EntityGraph(attributePaths = {"movie", "theater", "screenType", "user"})
    List<NotificationSubscription> findAllBySubscriptionIdIn(List<Long> subscriptionIds);

    /**
     * 발송 대상으로 선점. 아직 처리되지 않은(triggered=false) 경우에만 true 로 바꾼다.
     * 여러 서버가 같은 구독을 동시에 매칭해도 행 잠금 + 조건 재확인으로 한 곳만 1을 받는다.
     */
    @Modifying
    @Query("update NotificationSubscription s set s.triggered = true " +
           "where s.subscriptionId = :id and s.triggered = false")
    int claimTriggered(@Param("id") Long subscriptionId);
}
