package domain.telegram.service;

import domain.movie.dto.ScreeningResult;
import domain.telegram.entity.Subscriber;
import domain.telegram.repository.SubscriberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SRP : 텔레그램 Bot API 메시지 발송만 담당.
 * sendAlert() → DB의 전체 활성 구독자에게 브로드캐스트.
 * sendMessage(chatId, text) → 특정 사용자에게 직접 발송 (폴링 응답용).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramService {

    private final RestTemplate restTemplate;
    private final SubscriberRepository subscriberRepository;

    @Value("${telegram.bot.token}")
    private String botToken;

    /** 새 상영 발견 시 모든 활성 구독자에게 알림 발송 */
    public void sendAlert(ScreeningResult result) {
        List<Subscriber> subscribers = subscriberRepository.findAllByActiveTrue();
        if (subscribers.isEmpty()) {
            log.debug("구독자 없음 — 알림 스킵");
            return;
        }
        String message = format(result);
        subscribers.forEach(sub -> sendMessage(sub.getChatId(), message));
    }

    /** 특정 chatId에 메시지 발송 (폴링 응답, 개별 알림용) */
    public void sendMessage(String chatId, String text) {
        if (botToken.startsWith("YOUR_")) {
            log.warn("[텔레그램 미설정] → chatId={}, 메시지:\n{}", chatId, text);
            return;
        }

        String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
        Map<String, String> body = new HashMap<>();
        body.put("chat_id", chatId);
        body.put("text", text);
        body.put("parse_mode", "HTML");

        try {
            restTemplate.postForObject(url, body, Map.class);
            log.info("텔레그램 전송 완료 → chatId={}", chatId);
        } catch (Exception e) {
            log.error("텔레그램 전송 실패 chatId={}: {}", chatId, e.getMessage());
        }
    }

    private String format(ScreeningResult r) {
        String seats = r.availableSeats() < 0 ? "정보 없음" : r.availableSeats() + "석";
        return String.format("""
                🎬 <b>새 상영 알림</b>

                영화: %s
                영화관: %s | %s
                일시: %s %s
                잔여석: %s

                👉 <a href="%s">예매하기</a>""",
                r.movieTitle(),
                r.cinema(), r.theater(),
                r.date(), r.time() != null ? r.time() : "",
                seats,
                r.bookingUrl()
        );
    }
}
