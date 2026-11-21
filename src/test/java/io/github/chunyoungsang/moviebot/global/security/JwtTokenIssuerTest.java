package io.github.chunyoungsang.moviebot.global.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JwtTokenIssuer / JwtProperties")
class JwtTokenIssuerTest {

    private static final String SECRET = "test-only-secret-0123456789abcdef-0123456789";

    @Test
    @DisplayName("발급한 토큰은 같은 키로 검증되고 subject 가 사용자 식별자다")
    void issuedTokenIsVerifiable() {
        JwtTokenIssuer issuer = issuer(SECRET, Clock.systemUTC());

        JwtTokenIssuer.IssuedToken token = issuer.issue("5550001111");
        Jwt jwt = decoder(SECRET).decode(token.accessToken());

        assertThat(jwt.getSubject()).isEqualTo("5550001111");
        assertThat(token.expiresInSeconds()).isEqualTo(3600);
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofHours(1));
    }

    @Test
    @DisplayName("다른 키로 만든 토큰은 거부 (위조)")
    void tokenSignedWithOtherKeyIsRejected() {
        String forged = issuer("attacker-secret-0123456789abcdef-0123456789", Clock.systemUTC()).issue("5550001111").accessToken();

        assertThatThrownBy(() -> decoder(SECRET).decode(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("만료된 토큰은 거부")
    void expiredTokenIsRejected() {
        Clock twoHoursAgo = Clock.fixed(Instant.now().minus(Duration.ofHours(2)), ZoneOffset.UTC);
        String expired = issuer(SECRET, twoHoursAgo).issue("5550001111").accessToken();

        assertThatThrownBy(() -> decoder(SECRET).decode(expired)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("서명 키가 32바이트보다 짧으면 시작할 때 실패 (HS256 최소 길이)")
    void shortSecretFailsFast() {
        assertThatThrownBy(() -> new JwtProperties("too-short", Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static JwtTokenIssuer issuer(String secret, Clock clock) {
        return new JwtTokenIssuer(new NimbusJwtEncoder(new ImmutableSecret<>(key(secret))),
                new JwtProperties(secret, Duration.ofHours(1)), clock);
    }

    private static JwtDecoder decoder(String secret) {
        return NimbusJwtDecoder.withSecretKey(key(secret)).macAlgorithm(MacAlgorithm.HS256).build();
    }

    private static SecretKey key(String secret) {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
