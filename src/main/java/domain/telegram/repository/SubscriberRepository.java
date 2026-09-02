package domain.telegram.repository;

import domain.telegram.entity.Subscriber;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SubscriberRepository extends JpaRepository<Subscriber, Long> {

    Optional<Subscriber> findByChatId(String chatId);

    List<Subscriber> findAllByActiveTrue();
}