-- ShedLock: 다중 서버에서 @Scheduled 작업을 한 대에서만 실행하기 위한 락 테이블
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at  TIMESTAMP(3) NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);

-- 텔레그램 getUpdates 의 다음 offset (서버 간 공유)
-- 메모리에만 두면 폴링 락을 잡는 서버가 바뀔 때 이미 처리한 명령을 다시 받는다.
CREATE TABLE telegram_polling_offset (
    id          INT    NOT NULL PRIMARY KEY,
    next_offset BIGINT NOT NULL
);
INSERT INTO telegram_polling_offset (id, next_offset) VALUES (1, 0);
