# 🎬 영화 예매 오픈 알리미 (Movie Bot)

[![CI](https://github.com/CHUNYOUNGSANG/movie/actions/workflows/ci.yml/badge.svg)](https://github.com/CHUNYOUNGSANG/movie/actions/workflows/ci.yml)

CGV 상영 일정을 주기적으로 크롤링해서, 신청한 조건(영화 / 지점 / 상영관 / 날짜)의 **예매가 열리면 텔레그램으로 바로 알려주는 봇**입니다.

## 주요 기능

텔레그램 봇에 명령어를 보내 사용합니다.

| 명령어 | 설명 |
|---|---|
| `/start` | 알림 구독 시작 |
| `/add [영화] [날짜] [지점] [관]` | 알림 신청 (지점, 관은 선택). 예: `/add 위키드 2026-12-24 영등포 IMAX` |
| `/list` | 대기 중인 알림 목록 |
| `/history` | 완료된 알림 목록 |
| `/cancel [번호]` | 알림 취소 |
| `/stop` | 구독 해제 |

## 기술 스택

- **Backend**: Java 17, Spring Boot 4.1, Spring Data JPA, Flyway
- **DB**: MySQL 8.4 (운영 / 로컬·테스트는 Testcontainers)
- **Crawling**: Playwright (Chromium)
- **Messaging**: Telegram Bot API
- **Infra**: Docker, Docker Compose

## 동작 흐름

```mermaid
flowchart LR
    S["@Scheduled (5분)"] --> P[미발송 구독 조회]
    P --> G["영화|지점|날짜로 그룹핑"]
    G --> C["CGV 크롤링 (Playwright)<br/>트랜잭션 밖"]
    C --> D["스케줄 저장 / 구독 매칭<br/>짧은 트랜잭션"]
    D -- 같은 트랜잭션 --> O[(Outbox)]
    D -- AFTER_COMMIT 이벤트 --> L["발송 리스너 (@Async)"]
    O -. 누락분/재시도 .-> R["Relay 스케줄러"]
    L --> T[Telegram API]
    R --> T
```

## 주요 개선 사항

### 알림 유실·중복 방지와 트랜잭션 범위 분리

크롤링(외부 I/O)과 발송이 하나의 긴 트랜잭션에 묶여 있던 구조를 통합 테스트로 재현한 뒤 개선했습니다.

| 시나리오 | Before | After |
|---|---|---|
| 크롤링 중 점유 중인 DB 커넥션 | 1개 | **0개** |
| 처리 도중 예외 → 다음 주기 | 같은 알림 2회 발송 | **1회** |
| 매칭 트랜잭션 롤백 | 롤백됐는데 발송됨 | **발송 안 됨** |
| 텔레그램 발송 실패 | SUCCESS 기록, 영구 유실 | **FAIL 기록 후 재시도** |

- Playwright 크롤링을 트랜잭션 밖으로 분리하고 DB 작업만 짧은 트랜잭션으로 처리
- `@TransactionalEventListener(AFTER_COMMIT)`으로 커밋된 경우에만 발송
- 발송 결과(SUCCESS / FAIL)를 기록하고, 실패하면 다음 주기에 재시도

👉 상세 정리 (문제 → 원인 → 해결 → 결과, 트러블슈팅): 노션 링크 추가 예정

### Transactional Outbox와 재시도 정책

이벤트만으로는 커밋 직후 서버가 죽으면 알림이 사라지고, 실패 시 무한 재시도하던 문제를 개선했습니다.

| 시나리오 (스케줄러 5주기) | Before | After |
|---|---|---|
| 커밋 직후 발송 리스너 유실 | 영구 유실 | **relay 가 발송** |
| 텔레그램 일시 장애(5xx) 지속 | 5회 (무한) | **최대 횟수 후 중단** |
| 사용자가 봇 차단(403) | 5회 (무한) | **1회 후 중단** |

- 구독 상태 변경과 보낼 메시지를 같은 트랜잭션으로 `notification_outbox`에 기록
- 커밋 후 리스너가 즉시 발송하고, 누락분/재시도는 relay 스케줄러가 처리 (조건부 UPDATE 로 중복 발송 방지)
- 재시도: 지수 백오프(30초 → 1분 → 2분 …), 최대 5회, 4xx(429 제외)는 영구 실패로 즉시 중단

👉 상세 정리: 노션 링크 추가 예정

### 크롤링 결과 저장 벌크 처리

결과 1건마다 영화/극장/상영관/스케줄을 조회하고 저장하던 구조를 개선했습니다. (실제 JDBC 실행 횟수 측정)

| 크롤링 결과 100건 저장 | Before | After |
|---|---|---|
| 신규 저장 | 514회 | **8회** |
| 갱신 (예매 오픈 여부 변경) | 500회 | **4회** |
| MySQL 8.4 실행 시간 (500건, 신규) | 366.4 ms | **20.9 ms** |
| MySQL 8.4 실행 시간 (500건, 갱신) | 318.6 ms | **20.8 ms** |

- 영화/극장/상영관은 중복 제거 후 IN 조회 1회씩, 없는 것만 생성 (`persist`로 merge 의 사전 SELECT 제거)
- 스케줄은 IDENTITY PK 라 Hibernate 배치가 불가해 `JdbcTemplate.batchUpdate` + `ON DUPLICATE KEY UPDATE` 로 upsert
- `hibernate.jdbc.batch_size`, MySQL `rewriteBatchedStatements=true` 설정

👉 상세 정리: 노션 링크 추가 예정

### 다중 서버 동시 실행 방지 (조건부 UPDATE + ShedLock)

서버를 2대 이상 띄웠을 때의 동시 실행 문제를 서버 2대를 흉내 낸 통합 테스트로 재현한 뒤 개선했습니다. (H2, MySQL 8.4 에서 수정 전/후 실행)

| 시나리오 (서버 2대 동시 실행) | Before | After |
|---|---|---|
| 같은 구독 동시 매칭 (H2) | 알림 2건 (중복 발송) | **1건** |
| 같은 구독 동시 매칭 (MySQL 8.4) | 데드락 → 매칭 트랜잭션 롤백 | **1건, 데드락 없음** |
| 크롤링 스케줄러 동시 실행 | 크롤링 2회 | **1회** |

- 읽은 값(`triggered`)으로 판단하던 것을 조건부 UPDATE 의 영향 행 수로 선점하도록 변경 (X 락을 먼저 잡아 외래키 S 락 → X 락 데드락도 제거)
- 크롤링·텔레그램 폴링은 ShedLock(DB 시간 기준)으로 한 서버에서만 실행, 폴링 offset 은 DB 로 옮겨 서버 간 공유
- Outbox relay 는 건별 선점이 있어 락 없이 여러 서버가 나눠 처리

👉 상세 정리: 노션 링크 추가 예정

## 실행 방법

```bash
cp .env.example .env        # TELEGRAM_BOT_TOKEN 등 입력
docker compose up -d --build
```

로컬 실행 / 테스트 (Docker 필요, MySQL 8.4 컨테이너가 자동으로 뜨고 종료 시 정리됨):

```bash
./gradlew bootTestRun       # Testcontainers MySQL 로 앱 실행
./gradlew test              # 통합 테스트도 MySQL 8.4 컨테이너에서 실행
```
