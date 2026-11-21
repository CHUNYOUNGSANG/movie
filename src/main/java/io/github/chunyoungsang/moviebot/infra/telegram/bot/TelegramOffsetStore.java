package io.github.chunyoungsang.moviebot.infra.telegram.bot;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 텔레그램 getUpdates 의 다음 offset 을 DB 에 저장해 서버 간에 공유한다.
 * 폴링은 ShedLock 으로 한 번에 한 서버만 수행하므로 단순 읽기/쓰기로 충분하다.
 */
@Component
@RequiredArgsConstructor
public class TelegramOffsetStore {

    private final JdbcTemplate jdbcTemplate;

    public long get() {
        Long value = jdbcTemplate.queryForObject(
                "SELECT next_offset FROM telegram_polling_offset WHERE id = 1", Long.class);
        return value != null ? value : 0L;
    }

    public void save(long nextOffset) {
        jdbcTemplate.update("UPDATE telegram_polling_offset SET next_offset = ? WHERE id = 1", nextOffset);
    }
}
