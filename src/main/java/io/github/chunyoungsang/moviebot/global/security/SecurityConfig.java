package io.github.chunyoungsang.moviebot.global.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * API 인증 설정.
 * - 로그인(/api/auth/**)은 누구나, 나머지 /api/** 는 Bearer 토큰(JWT) 필요
 * - 세션·쿠키를 쓰지 않으므로(STATELESS) CSRF 보호는 끈다 (브라우저가 자동으로 붙이는 인증 정보가 없다)
 * - /api/** 만 다룬다. 봇(텔레그램 폴링)은 HTTP 요청을 받지 않고, Actuator 는 외부에 공개하지 않는 8081 포트에 있다
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/**")
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/auth/**").permitAll()
                        .requestMatchers("/api/notifications/**").permitAll()   // 다음 단계에서 인증 필요로 전환
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable);
        return http.build();
    }

    @Bean
    public SecretKey jwtSecretKey(JwtProperties properties) {
        byte[] secret;
        if (properties.hasSecret()) {
            secret = properties.secret().getBytes(StandardCharsets.UTF_8);
        } else {
            secret = new byte[JwtProperties.MIN_SECRET_BYTES];
            new SecureRandom().nextBytes(secret);
            log.warn("auth.jwt.secret(AUTH_JWT_SECRET) 이 없어 임의의 서명 키를 만들었습니다. 재시작하면 발급한 토큰이 무효가 되고, 서버 여러 대에서는 쓸 수 없습니다");
        }
        return new SecretKeySpec(secret, "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
    }

    /** 서명(HS256)과 만료(exp)를 검증한다 */
    @Bean
    public JwtDecoder jwtDecoder(SecretKey jwtSecretKey) {
        return NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(MacAlgorithm.HS256).build();
    }
}
