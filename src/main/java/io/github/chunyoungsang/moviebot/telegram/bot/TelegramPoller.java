package io.github.chunyoungsang.moviebot.telegram.bot;

import io.github.chunyoungsang.moviebot.telegram.client.TelegramClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 3초마다 텔레그램에서 새 메시지를 받아 명령 분배기에 넘긴다 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramPoller {

    private final TelegramClient telegramClient;
    private final TelegramOffsetStore offsetStore;   // 서버 간 공유 offset (DB)
    private final BotCommandDispatcher dispatcher;

    /**
     * 서버가 여러 대여도 한 대에서만 폴링한다 (같은 명령이 서버마다 중복 처리되는 것을 방지).
     * offset 은 DB 에 저장해 공유하므로, 락을 잡는 서버가 바뀌어도 이미 처리한 명령을 다시 받지 않는다.
     */
    @Scheduled(fixedDelay = 3000)
    @SchedulerLock(name = "telegramPolling", lockAtMostFor = "PT30S", lockAtLeastFor = "PT2S")
    public void poll() {
        for (Map<String, Object> update : telegramClient.getUpdates(offsetStore.get())) {
            long updateId = ((Number) update.get("update_id")).longValue();
            offsetStore.save(updateId + 1);   // 처리 전에 기록 (실패한 명령은 재처리하지 않음)
            try {
                dispatcher.processUpdate(update);
            } catch (Exception e) {
                // 한 명령의 실패가 같은 배치의 다음 명령 처리를 막지 않도록
                log.error("봇 명령 처리 실패 update_id={}: {}", updateId, e.getMessage());
            }
        }
    }
}
