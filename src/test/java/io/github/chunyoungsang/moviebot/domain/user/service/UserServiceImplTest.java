package io.github.chunyoungsang.moviebot.domain.user.service;

import io.github.chunyoungsang.moviebot.global.exception.BusinessException;
import io.github.chunyoungsang.moviebot.domain.user.exception.UserErrorCode;

import io.github.chunyoungsang.moviebot.domain.user.entity.User;
import io.github.chunyoungsang.moviebot.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserServiceImpl")
class UserServiceImplTest {

    @Mock private UserRepository userRepository;

    private UserServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new UserServiceImpl(userRepository);
    }

    @Nested
    @DisplayName("getByChatIdOrThrow()")
    class GetByChatIdOrThrow {

        @Test
        @DisplayName("있으면 반환한다")
        void returnsUser() {
            User user = User.builder().userId(1L).chatId("123").build();
            given(userRepository.findByChatId("123")).willReturn(Optional.of(user));

            assertThat(service.getByChatIdOrThrow("123")).isSameAs(user);
        }

        @Test
        @DisplayName("없으면 예외를 던진다")
        void throwsWhenAbsent() {
            given(userRepository.findByChatId("unknown")).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.getByChatIdOrThrow("unknown"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(UserErrorCode.USER_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("activate()")
    class Activate {

        @Test
        @DisplayName("없던 사용자면 새로 만들고 CREATED를 반환한다")
        void createsNewUser() {
            given(userRepository.findByChatId("111")).willReturn(Optional.empty());
            given(userRepository.save(any())).willAnswer(i -> i.getArgument(0));

            UserService.ActivationResult result = service.activate("111", "chun");

            assertThat(result.status()).isEqualTo(UserService.ActivationResult.Status.CREATED);
            assertThat(result.user().getChatId()).isEqualTo("111");
            assertThat(result.user().isActive()).isTrue();
        }

        @Test
        @DisplayName("비활성 사용자면 재활성화하고 REACTIVATED를 반환한다")
        void reactivatesInactiveUser() {
            User inactive = User.builder().userId(1L).chatId("111").active(false).build();
            given(userRepository.findByChatId("111")).willReturn(Optional.of(inactive));

            UserService.ActivationResult result = service.activate("111", "chun");

            assertThat(result.status()).isEqualTo(UserService.ActivationResult.Status.REACTIVATED);
            assertThat(inactive.isActive()).isTrue();
            then(userRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("이미 활성 사용자면 ALREADY_ACTIVE를 반환한다")
        void alreadyActiveUser() {
            User active = User.builder().userId(1L).chatId("111").active(true).build();
            given(userRepository.findByChatId("111")).willReturn(Optional.of(active));

            UserService.ActivationResult result = service.activate("111", "chun");

            assertThat(result.status()).isEqualTo(UserService.ActivationResult.Status.ALREADY_ACTIVE);
        }
    }

    @Nested
    @DisplayName("deactivate()")
    class Deactivate {

        @Test
        @DisplayName("있으면 비활성화하고 반환한다")
        void deactivatesExistingUser() {
            User user = User.builder().userId(1L).chatId("111").active(true).build();
            given(userRepository.findByChatId("111")).willReturn(Optional.of(user));

            Optional<User> result = service.deactivate("111");

            assertThat(result).isPresent();
            assertThat(result.get().isActive()).isFalse();
        }

        @Test
        @DisplayName("없으면 empty를 반환한다")
        void returnsEmptyWhenAbsent() {
            given(userRepository.findByChatId("unknown")).willReturn(Optional.empty());

            assertThat(service.deactivate("unknown")).isEmpty();
        }
    }
}
