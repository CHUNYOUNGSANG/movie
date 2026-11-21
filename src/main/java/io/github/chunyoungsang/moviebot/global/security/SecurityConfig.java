package io.github.chunyoungsang.moviebot.global.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.github.chunyoungsang.moviebot.global.exception.BusinessException;
import io.github.chunyoungsang.moviebot.global.exception.CommonErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
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
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.HandlerExceptionResolver;

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
    public SecurityFilterChain apiSecurity(HttpSecurity http,
                                           @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) throws Exception {
        AuthenticationEntryPoint unauthorized = jsonUnauthorized(resolver);
        http.securityMatcher("/api/**")
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/auth/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(unauthorized))   // 토큰이 틀렸거나 만료
                .exceptionHandling(e -> e.authenticationEntryPoint(unauthorized))   // 토큰이 없음
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable);
        return http.build();
    }

    /**
     * 401 응답도 다른 API 오류와 같은 {code, message} 형식으로 (GlobalExceptionHandler 에 맡긴다).
     * WWW-Authenticate 헤더는 표준 Bearer 진입점이 채운다. 실패 이유(서명 불일치, 만료)는 응답에 싣지 않는다
     */
    private static AuthenticationEntryPoint jsonUnauthorized(HandlerExceptionResolver resolver) {
        BearerTokenAuthenticationEntryPoint bearer = new BearerTokenAuthenticationEntryPoint();
        return (request, response, authException) -> {
            bearer.commence(request, response, authException);
            resolver.resolveException(request, response, null,
                    new BusinessException(CommonErrorCode.UNAUTHORIZED, authException.getMessage()));
        };
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
