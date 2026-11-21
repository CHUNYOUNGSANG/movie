package io.github.chunyoungsang.moviebot.support;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 텔레그램 로그인 위젯이 주는 것과 같은 형식으로 서명한 로그인 정보를 만든다 (테스트용, 가짜 봇 토큰).
 * 서명: data_check_string = 'hash' 를 뺀 필드를 키 순서로 "key=value" 줄바꿈 연결,
 *       hash = hex(HMAC-SHA256(key = SHA256(봇 토큰), data_check_string))
 * https://core.telegram.org/widgets/login#checking-authorization
 */
public final class TelegramLoginFixture {

    /** 테스트 전용 가짜 봇 토큰 (실제 토큰 아님) */
    public static final String BOT_TOKEN = "123456:TEST-ONLY-FAKE-TOKEN";

    private TelegramLoginFixture() {
    }

    public static Map<String, String> signedFields(String telegramId, Instant authDate) {
        Map<String, String> fields = new TreeMap<>();
        fields.put("id", telegramId);
        fields.put("first_name", "테스트");
        fields.put("username", "user" + telegramId);
        fields.put("auth_date", String.valueOf(authDate.getEpochSecond()));
        fields.put("hash", sign(fields, BOT_TOKEN));
        return fields;
    }

    /** id 와 auth_date 는 텔레그램처럼 숫자로 */
    public static String signedJson(String telegramId, Instant authDate) {
        return signedFields(telegramId, authDate).entrySet().stream()
                .map(e -> "\"" + e.getKey() + "\":" + (e.getKey().equals("id") || e.getKey().equals("auth_date")
                        ? e.getValue() : "\"" + e.getValue() + "\""))
                .collect(Collectors.joining(",", "{", "}"));
    }

    public static String sign(Map<String, String> fields, String botToken) {
        try {
            String dataCheckString = new TreeMap<>(fields).entrySet().stream()
                    .filter(e -> !e.getKey().equals("hash"))
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining("\n"));
            byte[] secret = MessageDigest.getInstance("SHA-256").digest(botToken.getBytes(StandardCharsets.UTF_8));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(dataCheckString.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
