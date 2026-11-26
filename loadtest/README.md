# 부하 테스트

운영 스택과 따로 띄우는 실험용 스택입니다. **실제 텔레그램과 CGV 는 호출하지 않습니다.**

| 구성 | 대신하는 것 |
|---|---|
| `fake-telegram` (`loadtest/fake-telegram/server.py`) | 텔레그램 Bot API. 봇 전체 발송이 초당 `LIMIT_PER_SEC`(30)를 넘으면 `429 retry_after` 로 거절 |
| `LoadTestMovieCrawler` (`loadtest` 프로필) | CGV 크롤러. `LOADTEST_CRAWL_DELAY` 만큼 기다린 뒤 "예매 열림" 상영 하나를 돌려줌 |
| `db` (tmpfs) | 실험이 끝나면 버리는 MySQL |
| `prometheus` (5초 수집), `grafana` | 운영과 같은 대시보드. 포트만 다름 |

## 실행

```bash
docker compose -f docker-compose.loadtest.yml up -d --build
```

| 화면 | 주소 |
|---|---|
| Grafana (로그인 없음) | http://localhost:3001 → Dashboards → Moviebot → Moviebot 운영 |
| Prometheus | http://localhost:9091 |

## 데이터 넣기

사용자 `@users` 명이 영화 `@movies` 편에 나눠 신청합니다. 크롤링 그룹 수는 `@movies`, 발송할 알림 수는 `@users` 입니다.
신청 확인 주기는 1분(`LOADTEST_CHECK_CRON`)이라 넣고 1분 안에 처리가 시작됩니다.

```bash
(echo "SET @users=5000, @movies=50, @date=CURDATE() + INTERVAL 8 DAY;"; cat loadtest/seed.sql) \
  | docker compose -f docker-compose.loadtest.yml exec -T db mysql -uroot -ploadtest moviebotdb
```

같은 실험을 다시 할 때는 `@date` 를 바꾸거나, 스택을 내렸다 올립니다 (DB 는 tmpfs 라 내리면 비워짐).

## 설정 바꿔 비교하기

`.env` 가 아니라 명령 앞에 환경 변수를 붙여 앱만 다시 띄웁니다.

| 변수 | 기본 | 의미 |
|---|---|---|
| `RATE_LIMIT_PER_SECOND` | 25 | 앱의 발송 속도 제한 (`telegram.rate-limit.per-second`) |
| `OUTBOX_MAX_ATTEMPTS` | 5 | 최대 발송 시도 횟수 |
| `LOADTEST_CRAWL_DELAY` | PT2S | 가짜 크롤링 한 번에 걸리는 시간 |
| `FAKE_TG_LIMIT_PER_SEC` | 30 | 가짜 텔레그램의 초당 한도 |
| `FAKE_TG_RETRY_AFTER` | 5 | 429 때 알려 주는 대기 시간(초) |

```bash
RATE_LIMIT_PER_SECOND=1000 docker compose -f docker-compose.loadtest.yml up -d app
```

## 개선 전/후 비교

`loadtest/measure.sh` 는 앱·DB·가짜 텔레그램만 새로 띄우고 Prometheus·Grafana 는 그대로 둡니다 (Prometheus 는 볼륨에 저장).
앱 이미지만 바꿔 연달아 측정하면 전후가 한 그래프에 이어서 남습니다.

```bash
# 비교할 이전 코드로 이미지를 따로 만든다 (예: 개선 전 커밋)
git worktree add /tmp/moviebot-before <커밋>
docker build -t moviebot-loadtest-app:before /tmp/moviebot-before
docker compose -f docker-compose.loadtest.yml build app        # 지금 코드 → moviebot-loadtest-app:latest

APP_IMAGE=moviebot-loadtest-app:before loadtest/measure.sh 5000 50 before
APP_IMAGE=moviebot-loadtest-app:latest loadtest/measure.sh 5000 50 after
```

결과는 `loadtest/results.csv` 에 쌓입니다 (커밋하지 않음). 지연은 Outbox 기록(매칭 커밋)부터 발송 완료까지로, 크롤링 주기 대기는 뺍니다.

## 숫자 뽑기

```promql
sum by (result) (notification_delivery_seconds_count)            # 발송 결과 누적
fake_telegram_send_total                                          # 가짜 텔레그램이 받은/거절한 요청
max_over_time(notification_outbox_oldest_pending_age_seconds[15m]) # 가장 오래 기다린 알림
max_over_time(notification_check_duration_seconds_max[15m])        # 확인 주기 최대 시간
max_over_time(hikaricp_connections_pending[15m])                   # 커넥션 대기
```

## 끝내기

```bash
docker compose -f docker-compose.loadtest.yml down
```

## 주의

- 측정값은 이 노트북에서 가짜 서버로 잰 값입니다. 운영 부하 수치처럼 쓰지 않습니다.
- 가짜 텔레그램의 한도(초당 30건)는 텔레그램 공식 안내 기준이며, 실제 텔레그램의 동작과 완전히 같지는 않습니다.
