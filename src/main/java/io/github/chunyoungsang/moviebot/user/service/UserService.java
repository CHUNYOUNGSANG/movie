package io.github.chunyoungsang.moviebot.user.service;

import io.github.chunyoungsang.moviebot.user.entity.User;

import java.util.Optional;

/**
 * SRP: 사용자 조회 및 구독 상태(활성/비활성) 변경 책임을 이 서비스로 모음.
 * 다른 도메인(Notification, Telegram)은 UserRepository를 직접 알지 못하고 이 인터페이스만 의존.
 */
public interface UserService {

    Optional<User> findByChatId(String chatId);

    User getByChatIdOrThrow(String chatId);

    ActivationResult activate(String chatId, String username);

    /** @return 비활성화된 User. chatId에 해당하는 사용자가 없으면 empty */
    Optional<User> deactivate(String chatId);

    record ActivationResult(User user, Status status) {
        public enum Status { CREATED, REACTIVATED, ALREADY_ACTIVE }
    }
}
