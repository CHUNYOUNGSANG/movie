package io.github.chunyoungsang.moviebot.global.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * API 토큰(JWT, HS256) 설정 (auth.jwt.*)
 *
 * @param secret 서명 키. 비어 있으면 시작할 때 임의로 만든다 (재시작하면 기존 토큰 무효, 서버 1대 전용)
 * @param ttl    토큰 유효 기간
 */
@ConfigurationProperties("auth.jwt")
public record JwtProperties(String secret, Duration ttl) {

    /** HS256 은 256비트 이상의 키를 요구한다 */
    static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        if (ttl == null) ttl = Duration.ofHours(1);
        if (ttl.isNegative() || ttl.isZero()) throw new IllegalArgumentException("auth.jwt.ttl 은 0보다 커야 합니다");
        if (hasText(secret) && secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException("auth.jwt.secret 은 " + MIN_SECRET_BYTES + "바이트 이상이어야 합니다");
        }
    }

    boolean hasSecret() {
        return hasText(secret);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
