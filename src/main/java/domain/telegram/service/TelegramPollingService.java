package domain.telegram.service;

import domain.telegram.entity.Subscriber;
import domain.telegram.repository.SubscriberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Telegram Bot API long-polling.
 * /start → 구독 등록, /stop → 구독 해제.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramPollingService {

    private final RestTemplate restTemplate;
    private final SubscriberRepository subscriberRepository;
    private final TelegramService telegramService;

    @Value("${telegram.bot.token}")
    private String botToken;

    private final AtomicLong offset = new AtomicLong(0);

    @Scheduled(fixedDelay = 3000)
    public void poll() {
        if (botToken.startsWith("YOUR_")) return;

        String url = "https://api.telegram.org/bot" + botToken
                + "/getUpdates?offset=" + offset.get() + "&timeout=1";

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            if (response == null || !Boolean.TRUE.equals(response.get("ok"))) return;

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> updates = (List<Map<String, Object>>) response.get("result");
            if (updates == null || updates.isEmpty()) return;

            for (Map<String, Object> update : updates) {
                long updateId = ((Number) update.get("update_id")).longValue();
                offset.set(updateId + 1);
                processUpdate(update);
            }
        } catch (Exception e) {
            log.error("텔레그램 폴링 오류: {}", e.getMessage());
        }
    }

    @Transactional
    public void processUpdate(Map<String, Object> update) {
        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) update.get("message");
        if (message == null) return;

        @SuppressWarnings("unchecked")
        Map<String, Object> chat = (Map<String, Object>) message.get("chat");
        if (chat == null) return;

        String chatId = String.valueOf(chat.get("id"));
        String username = (String) chat.getOrDefault("username", "");
        String text = (String) message.getOrDefault("text", "");

        if (text.startsWith("/start")) {
            handleStart(chatId, username);
        } else if (text.startsWith("/stop")) {
            handleStop(chatId);
        } else {
            telegramService.sendMessage(chatId,
                    "📋 사용 가능한 명령어:\n/start - 알림 구독\n/stop - 알림 해제");
        }
    }

    private void handleStart(String chatId, String username) {
        subscriberRepository.findByChatId(chatId).ifPresentOrElse(
                sub -> {
                    if (!sub.isActive()) {
                        sub.setActive(true);
                        log.info("구독 재활성화: chatId={}", chatId);
                        telegramService.sendMessage(chatId,
                                "✅ 알림 구독이 재활성화됐습니다!\n새로운 상영 일정이 생기면 알려드릴게요.");
                    } else {
                        telegramService.sendMessage(chatId, "이미 구독 중입니다 😊");
                    }
                },
                () -> {
                    subscriberRepository.save(Subscriber.builder()
                            .chatId(chatId)
                            .username(username)
                            .active(true)
                            .build());
                    log.info("신규 구독: chatId={}, username={}", chatId, username);
                    telegramService.sendMessage(chatId,
                            "🎬 영화 예매 알리미 구독 완료!\n새로운 상영 일정이 생기면 알려드릴게요.\n\n/stop 으로 언제든지 해제할 수 있어요.");
                }
        );
    }

    private void handleStop(String chatId) {
        subscriberRepository.findByChatId(chatId).ifPresentOrElse(
                sub -> {
                    sub.setActive(false);
                    log.info("구독 해제: chatId={}", chatId);
                    telegramService.sendMessage(chatId,
                            "🔕 알림 구독이 해제됐습니다.\n다시 받으시려면 /start 를 입력하세요.");
                },
                () -> telegramService.sendMessage(chatId, "구독 중이 아닙니다.")
        );
    }
}