package io.github.chunyoungsang.moviebot.infra.telegram.auth;

import io.github.chunyoungsang.moviebot.global.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 텔레그램 로그인 위젯이 준 사용자 정보가 진짜 텔레그램이 보낸 것인지 검증한다.
 * https://core.telegram.org/widgets/login#checking-authorization
 *
 *   data_check_string = 'hash' 를 뺀 모든 필드를 키 알파벳 순으로 "key=value" 줄바꿈(\n) 연결
 *   secret_key        = SHA256(봇 토큰)
 *   hash              = hex(HMAC-SHA256(secret_key, data_check_string))
 *
 * 봇 토큰은 우리 서버만 알기 때문에 hash 를 맞출 수 있는 것은 텔레그램뿐이다.
 * 서명이 맞아도 auth_date 가 오래됐으면 거부한다 (가로챈 로그인 정보의 재사용 방지).
 */
@Component
public class TelegramLoginVerifier {

    /** 서버 시각과 텔레그램 시각의 차이 허용 범위 (미래 시각의 auth_date) */
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(1);

    private final byte[] secretKey;
    private final Duration maxAge;
    private final Clock clock;

    @Autowired
    public TelegramLoginVerifier(@Value("${telegram.bot.token}") String botToken,
                                 @Value("${auth.telegram.max-age:PT24H}") Duration maxAge) {
        this(botToken, maxAge, Clock.systemUTC());
    }

    TelegramLoginVerifier(String botToken, Duration maxAge, Clock clock) {
        this.secretKey = isConfigured(botToken) ? sha256(botToken) : null;
        this.maxAge = maxAge;
        this.clock = clock;
    }

    /**
     * @param fields 로그인 위젯이 준 필드 그대로 (id, first_name, username, photo_url, auth_date, hash ...)
     *               텔레그램이 필드를 추가해도 서명이 깨지지 않도록 알려진 필드만 고르지 않고 전부 쓴다.
     * @throws BusinessException 서명이 틀렸거나(TELEGRAM_LOGIN_INVALID) 오래됐으면(TELEGRAM_LOGIN_EXPIRED)
     */
    public TelegramUser verify(Map<String, ?> fields) {
        if (secretKey == null) throw invalid("봇 토큰 미설정");
        Object hash = fields.get("hash");
        Object id = fields.get("id");
        Object authDate = fields.get("auth_date");
        if (hash == null || id == null || authDate == null) throw invalid("필수 필드 누락");

        byte[] expected = HexFormat.of().formatHex(hmac(dataCheckString(fields))).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = String.valueOf(hash).toLowerCase().getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, actual)) throw invalid("서명 불일치");   // 비교 시간으로 hash 를 추측하지 못하게

        Instant signedAt = parseEpochSeconds(authDate);
        Instant now = clock.instant();
        if (signedAt.isAfter(now.plus(CLOCK_SKEW))) throw invalid("미래 시각의 auth_date");
        if (signedAt.isBefore(now.minus(maxAge))) {
            throw new BusinessException(TelegramAuthErrorCode.TELEGRAM_LOGIN_EXPIRED, "auth_date=" + signedAt);
        }

        Object username = fields.get("username");
        return new TelegramUser(String.valueOf(id), username == null ? null : String.valueOf(username));
    }

    static String dataCheckString(Map<String, ?> fields) {
        return new TreeMap<>(fields).entrySet().stream()
                .filter(e -> !e.getKey().equals("hash") && e.getValue() != null)
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("\n"));
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 을 사용할 수 없음", e);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 을 사용할 수 없음", e);
        }
    }

    private static Instant parseEpochSeconds(Object value) {
        try {
            return Instant.ofEpochSecond(Long.parseLong(String.valueOf(value)));
        } catch (NumberFormatException e) {
            throw invalid("auth_date 형식 오류");
        }
    }

    private static boolean isConfigured(String botToken) {
        return botToken != null && !botToken.isBlank() && !botToken.startsWith("YOUR_");
    }

    private static BusinessException invalid(String detail) {
        return new BusinessException(TelegramAuthErrorCode.TELEGRAM_LOGIN_INVALID, detail);
    }

    /** 검증된 텔레그램 사용자. 개인 채팅에서는 텔레그램 사용자 id 가 곧 chatId 다 */
    public record TelegramUser(String telegramId, String username) {
    }
}
