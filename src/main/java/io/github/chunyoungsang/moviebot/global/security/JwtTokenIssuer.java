package io.github.chunyoungsang.moviebot.global.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * 인증된 사용자에게 API 토큰을 발급한다. 누가 인증됐는지(텔레그램 로그인 등)는 호출하는 쪽이 판단한다.
 * 토큰의 subject 가 API 에서 쓰는 사용자 식별자(chatId)다.
 */
@Component
public class JwtTokenIssuer {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;
    private final Clock clock;

    @Autowired
    public JwtTokenIssuer(JwtEncoder jwtEncoder, JwtProperties properties) {
        this(jwtEncoder, properties, Clock.systemUTC());
    }

    JwtTokenIssuer(JwtEncoder jwtEncoder, JwtProperties properties, Clock clock) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
        this.clock = clock;
    }

    public IssuedToken issue(String subject) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plus(properties.ttl()))
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new IssuedToken(token, properties.ttl().toSeconds());
    }

    public record IssuedToken(String accessToken, long expiresInSeconds) {
    }
}
