package io.github.chunyoungsang.moviebot.infra.telegram.auth;

import io.github.chunyoungsang.moviebot.global.security.JwtTokenIssuer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * POST /api/auth/telegram   텔레그램 로그인 위젯이 준 정보로 API 토큰 발급
 *
 * 요청 본문: 위젯이 준 필드 그대로 {"id":123,"first_name":"..","username":"..","auth_date":1700000000,"hash":".."}
 * 응답: {"accessToken":"..","tokenType":"Bearer","expiresIn":3600}
 * 이후 요청: Authorization: Bearer {accessToken}
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class TelegramAuthController {

    private final TelegramLoginVerifier verifier;
    private final JwtTokenIssuer tokenIssuer;

    @PostMapping("/telegram")
    public TokenResponse login(@RequestBody Map<String, Object> telegramLogin) {
        TelegramLoginVerifier.TelegramUser user = verifier.verify(telegramLogin);
        JwtTokenIssuer.IssuedToken token = tokenIssuer.issue(user.telegramId());
        log.info("텔레그램 로그인 telegramId={}", user.telegramId());
        return new TokenResponse(token.accessToken(), "Bearer", token.expiresInSeconds());
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
    }
}
