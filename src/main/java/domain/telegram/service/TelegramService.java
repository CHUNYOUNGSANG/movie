package domain.telegram.service;

import domain.movie.dto.ScreeningResult;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramService implements MessageSender {

    private final RestTemplate restTemplate;

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
            log.error("텔레그램 자동완성 메뉴 등록 실패: {}", e.getMessage());
        }
    }

    @Override
    public void sendMessage(String chatId, String text) {
        try {
            post(chatId, text);
        } catch (Exception e) {
            log.error("텔레그램 전송 실패 chatId={}: {}", chatId, e.getMessage());
        }
    }

    @Override
    public void sendAlerts(String chatId, List<ScreeningResult> results) {
        if (results.isEmpty()) return;
        try {
            post(chatId, formatAll(results));
        } catch (Exception e) {
            throw new MessageSendException("알림 발송 실패 chatId=" + chatId, e);
        }
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

        restTemplate.postForObject(url, body, Map.class);
        log.info("텔레그램 전송 완료 → chatId={}", chatId);
    }

    private String formatAll(List<ScreeningResult> results) {
        ScreeningResult first = results.get(0);

        StringBuilder sb = new StringBuilder();
        sb.append("🎬 <b>예매 오픈 알림!</b>\n\n");
        sb.append("영화: <b>").append(first.movieTitle()).append("</b>\n");
        sb.append("날짜: ").append(first.date()).append("\n");

        // 극장별로 그룹핑해서 출력
        Map<String, List<ScreeningResult>> byTheater = new LinkedHashMap<>();
        for (ScreeningResult r : results) {
            byTheater.computeIfAbsent(r.theater(), k -> new ArrayList<>()).add(r);
        }

        for (Map.Entry<String, List<ScreeningResult>> entry : byTheater.entrySet()) {
            sb.append("\n📍 <b>").append(entry.getKey()).append("</b>\n");
            for (ScreeningResult r : entry.getValue()) {
                String time  = r.time() != null ? r.time().toString() : "미정";
                String seats = r.availableSeats() < 0 ? "" : " (잔여 " + r.availableSeats() + "석)";
                sb.append("  ▸ ").append(time).append(seats).append("\n");
            }
        }

        sb.append("\n👉 <a href=\"").append(first.bookingUrl()).append("\">예매하기</a>");
        return sb.toString();
    }
}
