package io.github.chunyoungsang.moviebot.telegram.service;

import jakarta.annotation.PostConstruct;
import io.github.chunyoungsang.moviebot.notification.sender.MessageSendException;
import io.github.chunyoungsang.moviebot.notification.sender.MessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.time.Duration;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
/**
 * 텔레그램 Bot API 클라이언트.
 * - 알림 발송: 알림 도메인의 발송 포트(MessageSender)를 구현 (실패 시 예외)
 * - 봇 응답: 명령에 대한 답장 (실패해도 예외를 던지지 않음)
 */
public class TelegramService implements MessageSender {

    private final RestTemplate restTemplate;
    private final TelegramRateLimiter rateLimiter;

    @Value("${telegram.bot.token}")
    private String botToken;

    // 텔레그램 "/" 자동완성 메뉴에 노출할 명령어 목록 (텔레그램 규칙상 소문자 영문/숫자/밑줄만 허용)
    private static final List<Map<String, String>> COMMANDS = List.of(
            Map.of("command", "start", "description", "알림 구독 시작"),
            Map.of("command", "add", "description", "영화 알림 신청"),
            Map.of("command", "list", "description", "대기 중인 알림 목록"),
            Map.of("command", "history", "description", "완료된 알림 목록"),
            Map.of("command", "cancel", "description", "알림 취소"),
            Map.of("command", "stop", "description", "알림 구독 해제"),
            Map.of("command", "help", "description", "사용법 안내")
    );

    @PostConstruct
    public void registerCommands() {
        if (botToken.startsWith("YOUR_")) {
            log.warn("[텔레그램 미설정] 자동완성 메뉴 등록 스킵");
            return;
        }
        String url = "https://api.telegram.org/bot" + botToken + "/setMyCommands";
        try {
            restTemplate.postForObject(url, Map.of("commands", COMMANDS), Map.class);
            log.info("텔레그램 자동완성 메뉴 등록 완료");
        } catch (Exception e) {
            log.error("텔레그램 자동완성 메뉴 등록 실패: {}", redact(e));
        }
    }

    /** 봇 명령에 대한 답장. 실패해도 예외를 던지지 않는다 (로그만 남김). */
    public void sendMessage(String chatId, String text) {
        try {
            post(chatId, text);
        } catch (Exception e) {
            log.error("텔레그램 전송 실패 chatId={}: {}", chatId, redact(e));
        }
    }

    @Override
    public void deliver(String chatId, String text) {
        try {
            post(chatId, text);
        } catch (Exception e) {
            // 원본 예외(cause)는 붙이지 않는다: RestTemplate 예외 메시지에 토큰이 포함된 URL 이 들어 있어
            // 스택트레이스 로깅이나 DB(last_error) 저장 시 토큰이 노출될 수 있다
            throw new MessageSendException("알림 발송 실패 chatId=" + chatId + ": " + redact(e), null,
                    isRetryable(e), retryAfter(e));
        }
    }

    /** 예외 메시지에서 봇 토큰을 가린다 (요청 URL 이 메시지에 포함되는 경우가 있음) */
    private String redact(Exception e) {
        String message = e.getClass().getSimpleName() + ": " + e.getMessage();
        return botToken == null || botToken.isBlank() ? message : message.replace(botToken, "<redacted>");
    }

    /**
     * 4xx 는 요청 자체가 잘못된 것이므로 재시도해도 성공하지 않는다 (봇 차단 403, chat 없음 400 등).
     * 단 429(Too Many Requests)는 잠시 후 성공할 수 있으므로 재시도한다.
     * 5xx, 타임아웃 등 네트워크 오류는 일시 장애로 보고 재시도한다.
     */
    private static boolean isRetryable(Exception e) {
        if (e instanceof HttpClientErrorException clientError) {
            return clientError.getStatusCode().value() == 429;
        }
        return true;
    }

    /** 응답 본문 예: {"ok":false,"error_code":429,"parameters":{"retry_after":5}} */
    private static final Pattern RETRY_AFTER = Pattern.compile("\"retry_after\"\\s*:\\s*(\\d+)");
    private static final Duration DEFAULT_RETRY_AFTER = Duration.ofSeconds(1);

    /** 429 이면 텔레그램이 알려준 대기 시간(초), 값이 없으면 1초. 429 가 아니면 null */
    static Duration retryAfter(Exception e) {
        if (!(e instanceof HttpClientErrorException clientError) || clientError.getStatusCode().value() != 429) {
            return null;
        }
        Matcher m = RETRY_AFTER.matcher(clientError.getResponseBodyAsString());
        return m.find() ? Duration.ofSeconds(Long.parseLong(m.group(1))) : DEFAULT_RETRY_AFTER;
    }

    private void post(String chatId, String text) {
        if (botToken.startsWith("YOUR_")) {
            log.warn("[텔레그램 미설정] chatId={}, 메시지:\n{}", chatId, text);
            return;
        }

        String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
        Map<String, String> body = new HashMap<>();
        body.put("chat_id", chatId);
        body.put("text", text);
        body.put("parse_mode", "HTML");

        try {
            rateLimiter.acquire();   // 전역 발송 속도 제한 (초당 약 30건) 안에서 고르게 분산
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("발송 대기 중 인터럽트", e);
        }
        try {
            restTemplate.postForObject(url, body, Map.class);
        } catch (HttpClientErrorException e) {
            Duration retryAfter = retryAfter(e);
            if (retryAfter != null) {
                rateLimiter.pause(retryAfter);   // flood control 중에는 다른 스레드도 보내지 않도록 전체 멈춤
                log.warn("텔레그램 속도 제한(429): {}초 동안 발송 중단", retryAfter.toSeconds());
            }
            throw e;
        }
        log.info("텔레그램 전송 완료 → chatId={}", chatId);
    }
}
