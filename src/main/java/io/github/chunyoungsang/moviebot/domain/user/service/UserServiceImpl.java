package io.github.chunyoungsang.moviebot.domain.user.service;

import io.github.chunyoungsang.moviebot.domain.user.exception.UserErrorCode;
import io.github.chunyoungsang.moviebot.global.exception.BusinessException;

import io.github.chunyoungsang.moviebot.domain.user.entity.User;
import io.github.chunyoungsang.moviebot.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<User> findByChatId(String chatId) {
        return userRepository.findByChatId(chatId);
    }

    @Override
    @Transactional(readOnly = true)
    public User getByChatIdOrThrow(String chatId) {
        return userRepository.findByChatId(chatId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND, "chatId=" + chatId));
    }

    @Override
    public ActivationResult activate(String chatId, String username) {
        return userRepository.findByChatId(chatId)
                .map(user -> {
                    if (user.isActive()) {
                        return new ActivationResult(user, ActivationResult.Status.ALREADY_ACTIVE);
                    }
                    user.setActive(true);
                    return new ActivationResult(user, ActivationResult.Status.REACTIVATED);
                })
                .orElseGet(() -> {
                    User created = userRepository.save(
                            User.builder().chatId(chatId).username(username).active(true).build());
                    return new ActivationResult(created, ActivationResult.Status.CREATED);
                });
    }

    @Override
    public Optional<User> deactivate(String chatId) {
        return userRepository.findByChatId(chatId)
                .map(user -> {
                    user.setActive(false);
                    return user;
                });
    }
}
