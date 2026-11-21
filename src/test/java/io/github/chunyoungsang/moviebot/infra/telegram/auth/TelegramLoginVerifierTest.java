package io.github.chunyoungsang.moviebot.infra.telegram.auth;

import io.github.chunyoungsang.moviebot.global.exception.BusinessException;
import io.github.chunyoungsang.moviebot.global.exception.ErrorCode;
import io.github.chunyoungsang.moviebot.support.TelegramLoginFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TelegramLoginVerifier")
class TelegramLoginVerifierTest {

    private static final Instant NOW = Instant.parse("2026-12-24T10:00:00Z");
    private final TelegramLoginVerifier verifier = new TelegramLoginVerifier(
            TelegramLoginFixture.BOT_TOKEN, Duration.ofHours(24), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("텔레그램 방식으로 서명된 정보면 사용자 id 를 돌려준다")
    void validSignature() {
        TelegramLoginVerifier.TelegramUser user = verifier.verify(TelegramLoginFixture.signedFields("5550001111", NOW.minusSeconds(60)));

        assertThat(user.telegramId()).isEqualTo("5550001111");
        assertThat(user.username()).isEqualTo("user5550001111");
    }

    @Test
    @DisplayName("id 를 남의 것으로 바꾸면 서명이 맞지 않는다 (위조)")
    void tamperedId() {
        Map<String, String> fields = new HashMap<>(TelegramLoginFixture.signedFields("5550001111", NOW));
        fields.put("id", "5550002222");

        assertError(fields, TelegramAuthErrorCode.TELEGRAM_LOGIN_INVALID);
    }

    @Test
    @DisplayName("다른 봇 토큰으로 서명한 정보는 거부 (다른 봇의 로그인 정보 재사용 방지)")
    void signedWithOtherBotToken() {
        Map<String, String> fields = new HashMap<>(TelegramLoginFixture.signedFields("5550001111", NOW));
        fields.put("hash", TelegramLoginFixture.sign(fields, "999:OTHER-BOT-TOKEN"));

        assertError(fields, TelegramAuthErrorCode.TELEGRAM_LOGIN_INVALID);
    }

    @Test
    @DisplayName("서명이 맞아도 유효 기간(24시간)이 지났으면 만료")
    void expired() {
        assertError(TelegramLoginFixture.signedFields("5550001111", NOW.minus(Duration.ofHours(25))),
                TelegramAuthErrorCode.TELEGRAM_LOGIN_EXPIRED);
    }

    @Test
    @DisplayName("auth_date 가 미래(허용 오차 1분 초과)면 거부")
    void futureAuthDate() {
        assertError(TelegramLoginFixture.signedFields("5550001111", NOW.plus(Duration.ofMinutes(5))),
                TelegramAuthErrorCode.TELEGRAM_LOGIN_INVALID);
    }

    @Test
    @DisplayName("hash 나 필수 필드가 없으면 거부")
    void missingFields() {
        Map<String, String> fields = new HashMap<>(TelegramLoginFixture.signedFields("5550001111", NOW));
        fields.remove("hash");

        assertError(fields, TelegramAuthErrorCode.TELEGRAM_LOGIN_INVALID);
    }

    @Test
    @DisplayName("텔레그램이 새 필드를 추가해도 받은 필드 전부로 검증하므로 통과한다")
    void unknownFieldsAreSigned() {
        Map<String, String> fields = new HashMap<>(TelegramLoginFixture.signedFields("5550001111", NOW));
        fields.put("photo_url", "https://t.me/i/userpic/320/abc.jpg");
        fields.put("hash", TelegramLoginFixture.sign(fields, TelegramLoginFixture.BOT_TOKEN));

        assertThat(verifier.verify(fields).telegramId()).isEqualTo("5550001111");
    }

    @Test
    @DisplayName("봇 토큰이 설정되지 않은 환경에서는 어떤 로그인도 받지 않는다")
    void botTokenNotConfigured() {
        TelegramLoginVerifier unconfigured = new TelegramLoginVerifier("YOUR_BOT_TOKEN_HERE", Duration.ofHours(24),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> unconfigured.verify(TelegramLoginFixture.signedFields("1", NOW)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("data_check_string 은 hash 를 빼고 키 순서로 줄바꿈 연결 (텔레그램 문서의 형식)")
    void dataCheckStringFormat() {
        assertThat(TelegramLoginVerifier.dataCheckString(Map.of("username", "a", "id", "1", "auth_date", "9", "hash", "x")))
                .isEqualTo("auth_date=9\nid=1\nusername=a");
    }

    private void assertError(Map<String, ?> fields, ErrorCode code) {
        assertThatThrownBy(() -> verifier.verify(fields))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(code);
    }
}
