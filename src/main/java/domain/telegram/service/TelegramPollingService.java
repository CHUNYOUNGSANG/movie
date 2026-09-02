package domain.telegram.service;

import domain.notification.dto.NotificationRegisterRequest;
import domain.notification.entity.NotificationRequest;
import domain.notification.repository.NotificationRequestRepository;
import domain.notification.service.NotificationService;
import domain.user.entity.User;
import domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramPollingService {

    private final RestTemplate restTemplate;
    private final UserRepository userRepository;
    private final NotificationRequestRepository notificationRequestRepository;
    private final NotificationService notificationService;
    private final TelegramService telegramService;

    @Value("${telegram.bot.token}")
    private String botToken;

    private final AtomicLong offset = new AtomicLong(0);

    private static final String HELP_TEXT = """
            🎬 <b>영화 예매 알리미 사용법</b>

            <b>기본 명령어</b>
            /start — 알림 구독 시작
            /stop  — 알림 구독 해제
            /list  — 내 알림 신청 목록
            /help  — 사용법 안내

            <b>알림 신청</b>
            /add [영화제목] [날짜] [지점(선택)] [관종류(선택)]

            <b>예시</b>
            /add 스파이더맨 2026-09-04
            /add 스파이더맨 2026-09-04 영등포
            /add 스파이더맨 2026-09-04 영등포 IMAX

            <b>알림 취소</b>
            /cancel [번호]  ← /list 에서 확인한 번호

            📌 예매가 오픈되면 자동으로 알림을 드립니다!
            """;

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

        String chatId   = String.valueOf(chat.get("id"));
        String username = (String) chat.getOrDefault("username", "");
        String text     = (String) message.getOrDefault("text", "");

        if (text.startsWith("/start"))        handleStart(chatId, username);
        else if (text.startsWith("/stop"))    handleStop(chatId);
        else if (text.startsWith("/list"))    handleList(chatId);
        else if (text.startsWith("/add"))     handleAdd(chatId, text);
        else if (text.startsWith("/cancel"))  handleCancel(chatId, text);
        else if (text.startsWith("/help"))    telegramService.sendMessage(chatId, HELP_TEXT);
        else telegramService.sendMessage(chatId, HELP_TEXT);
    }

    // ── 명령어 핸들러 ─────────────────────────────────────────

    private void handleStart(String chatId, String username) {
        userRepository.findByChatId(chatId).ifPresentOrElse(
                user -> {
                    if (!user.isActive()) {
                        user.setActive(true);
                        telegramService.sendMessage(chatId, "✅ 구독이 재활성화됐습니다!\n\n" + HELP_TEXT);
                    } else {
                        telegramService.sendMessage(chatId, "이미 구독 중입니다 😊\n\n" + HELP_TEXT);
                    }
                },
                () -> {
                    userRepository.save(User.builder()
                            .chatId(chatId).username(username).active(true).build());
                    log.info("신규 구독: chatId={}, username={}", chatId, username);
                    telegramService.sendMessage(chatId, "🎉 구독 완료!\n\n" + HELP_TEXT);
                }
        );
    }

    private void handleStop(String chatId) {
        userRepository.findByChatId(chatId).ifPresentOrElse(
                user -> {
                    user.setActive(false);
                    log.info("구독 해제: chatId={}", chatId);
                    telegramService.sendMessage(chatId,
                            "🔕 구독이 해제됐습니다. 다시 받으시려면 /start 를 입력하세요.");
                },
                () -> telegramService.sendMessage(chatId, "구독 중이 아닙니다.")
        );
    }

    private void handleList(String chatId) {
        userRepository.findByChatId(chatId).ifPresentOrElse(
                user -> {
                    List<NotificationRequest> requests =
                            notificationRequestRepository.findByUser(user);
                    if (requests.isEmpty()) {
                        telegramService.sendMessage(chatId,
                                "등록된 알림이 없습니다.\n/add 명령어로 등록해보세요!");
                        return;
                    }
                    StringBuilder sb = new StringBuilder("📋 <b>내 알림 목록</b>\n\n");
                    for (NotificationRequest req : requests) {
                        sb.append(String.format(
                                "#%d %s | %s | %s | %s  %s\n",
                                req.getRequestId(),
                                req.getMovie().getTitle(),
                                req.getTargetDate(),
                                req.getTheater() != null ? req.getTheater().getBranchName() : "전체 지점",
                                req.getScreenType() != null ? req.getScreenType().getTypeName() : "전체 관",
                                req.isTriggered() ? "✅완료" : "⏳대기"
                        ));
                    }
                    sb.append("\n취소하려면: /cancel [번호]");
                    telegramService.sendMessage(chatId, sb.toString());
                },
                () -> telegramService.sendMessage(chatId, "/start 를 먼저 입력해주세요.")
        );
    }

    private void handleAdd(String chatId, String text) {
        // /add [영화제목] [날짜] [지점(선택)] [관종류(선택)]
        String[] parts = text.substring("/add".length()).trim().split("\\s+");
        if (parts.length < 2 || parts[0].isBlank()) {
            telegramService.sendMessage(chatId, """
                    ❌ 형식이 올바르지 않습니다.

                    사용법: /add [영화제목] [날짜] [지점(선택)] [관종류(선택)]
                    예시: /add 스파이더맨 2026-09-04 영등포 IMAX""");
            return;
        }

        String movieTitle = parts[0];
        LocalDate targetDate;
        try {
            targetDate = LocalDate.parse(parts[1]);
        } catch (DateTimeParseException e) {
            telegramService.sendMessage(chatId,
                    "❌ 날짜 형식이 올바르지 않습니다.\n날짜는 YYYY-MM-DD 형식으로 입력해주세요.\n예시: 2026-09-04");
            return;
        }

        String branchName    = parts.length > 2 ? parts[2] : null;
        String screenTypeName = parts.length > 3 ? parts[3] : null;

        try {
            notificationService.register(
                    new NotificationRegisterRequest(
                            chatId, movieTitle, "CGV", branchName, screenTypeName, targetDate));

            telegramService.sendMessage(chatId, String.format("""
                    ✅ <b>알림 신청 완료!</b>

                    영화: %s
                    날짜: %s
                    지점: %s
                    관종류: %s

                    예매가 오픈되면 바로 알려드릴게요 🎬""",
                    movieTitle, targetDate,
                    branchName != null ? branchName : "전체 지점",
                    screenTypeName != null ? screenTypeName : "전체 관"
            ));
            log.info("알림 신청 (텔레그램): chatId={}, movie={}, date={}", chatId, movieTitle, targetDate);
        } catch (IllegalArgumentException e) {
            telegramService.sendMessage(chatId, "❌ " + e.getMessage());
        }
    }

    private void handleCancel(String chatId, String text) {
        String idStr = text.substring("/cancel".length()).trim();
        if (idStr.isBlank()) {
            telegramService.sendMessage(chatId,
                    "취소할 번호를 입력해주세요.\n예시: /cancel 1\n\n목록 확인: /list");
            return;
        }
        try {
            Long requestId = Long.parseLong(idStr);
            notificationService.cancel(requestId);
            telegramService.sendMessage(chatId, "🗑 #" + requestId + " 알림이 취소됐습니다.");
        } catch (NumberFormatException e) {
            telegramService.sendMessage(chatId,
                    "❌ 번호가 올바르지 않습니다.\n예시: /cancel 1\n\n목록 확인: /list");
        } catch (IllegalArgumentException e) {
            telegramService.sendMessage(chatId,
                    "❌ 해당 번호의 알림을 찾을 수 없습니다.\n현재 목록을 확인해주세요: /list");
        }
    }
}