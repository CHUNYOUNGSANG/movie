# 배포·운영 가이드

서버 한 대에 `docker compose` 로 띄우고, 몇 주 운영하면서 **실제 운영 수치**를 모으는 절차입니다.
README 의 전후 수치는 모두 통합 테스트로 재현한 값입니다. 이 문서는 그 옆에 놓을 "실제로 돌려 보니" 수치를 만드는 방법을 다룹니다.

## 1. 준비

| 항목 | 권장 |
|---|---|
| 서버 | 2 vCPU / 4GB RAM 이상 (Chromium + MySQL). 예: 클라우드 소형 VM, 집 서버 |
| OS | Docker Engine 24+ 와 Compose v2 가 설치된 Linux |
| 방화벽 | **3306 은 외부에 열지 않는다** (compose 가 호스트에 공개하므로 방화벽에서 막거나 `ports` 를 지운다). 8080 은 REST API 를 쓸 때만 연다 |
| 텔레그램 | BotFather 에서 **운영용 봇**을 따로 만든다 (개발용 토큰과 분리) |

## 2. 설정과 첫 실행

```bash
git clone https://github.com/CHUNYOUNGSANG/movie.git && cd movie
cp .env.example .env
```

`.env` 에서 바꿀 값:

| 변수 | 설명 |
|---|---|
| `DB_ROOT_PASSWORD`, `DB_PASSWORD` | 예시 값을 그대로 쓰지 않는다 (`openssl rand -base64 24`) |
| `TELEGRAM_BOT_TOKEN` | 운영용 봇 토큰 |
| `AUTH_JWT_SECRET` | `openssl rand -base64 48`. 비워 두면 재시작할 때마다 로그인이 풀린다 |
| `MOVIE_CHECK_CRON` | 기본 5분. CGV 에 부담을 주지 않도록 더 짧게 하지 않는다 |

```bash
chmod 600 .env
docker compose up -d --build
docker compose ps                       # app 이 (healthy) 가 될 때까지 최대 약 90초
docker compose logs -f app | head -50   # Flyway V1~V6 적용, "Started MovieBotApplication" 확인
```

> 이전 버전(root 로 실행하던 이미지)으로 띄운 적이 있다면 `playwright-browsers` 볼륨이 root 소유로 남아 있어 브라우저를 못 쓸 수 있다.
> `docker compose down && docker volume rm movie_playwright-browsers && docker compose up -d --build` 로 다시 만든다 (볼륨 이름은 `docker volume ls` 로 확인).

### 첫 주기 확인 (non-root 이미지에서 실제 Chromium 크롤링은 여기서 처음 확인된다)

1. 봇에 `/start`, 그리고 예매가 이미 열린 영화로 `/add [영화] [날짜] [지점]`
2. 5분 안에 알림이 오는지 본다
3. 로그에 크롤링 오류가 없는지 본다

```bash
docker compose logs app --since 10m | grep -Ei "crawl|error|warn"
```

## 3. 지표 보기

관리 포트 8081 은 호스트에 공개하지 않는다. 컨테이너 안에서 조회한다.

```bash
docker compose exec app curl -s localhost:8081/actuator/health
docker compose exec app curl -s localhost:8081/actuator/prometheus | grep -E "^(crawl|notification|retention)_"
```

| 지표 (Prometheus 이름) | 의미 | 볼 것 |
|---|---|---|
| `crawl_duration_seconds_{count,sum,max}{outcome}` | 그룹별 크롤링 소요 시간과 결과 | 평균(`sum/count`)·최대가 평소 대비 갑자기 늘어나는지 |
| `crawl_errors_total{stage,reason}` | 크롤링 실패 (차단, 브라우저, API 오류) | 0 이 아니면 원인별로 확인. `stage="cloudflare"` 또는 `reason="http_403"` 은 차단 의심 |
| `crawl_cycle_timeout_total` | 주기 제한 시간(8분) 초과 | 0 이어야 정상. 늘면 그룹 수 대비 동시 크롤링 수가 부족 |
| `notification_check_duration_seconds_{count,sum,max}` | 확인 주기 전체 소요 시간 | 5분 주기보다 충분히 짧은지 |
| `notification_delivery_seconds_count{result}` | 발송 결과 (sent·retry·rate_limited·dead·canceled) | `dead` 가 늘면 원인 확인 |
| `notification_outbox_messages{status}` | 상태별 Outbox 건수 | `PENDING` 이 계속 쌓이지 않는지 |
| `notification_outbox_oldest_pending_age_seconds` | 가장 오래 기다린 알림 | 1분 이상 지속되면 발송 장애 |
| `retention_deleted_total{table}` | 보관 정책으로 지운 행 수 (테이블별) | 매일 03:30 이후 증가 |

Prometheus 서버를 따로 띄운다면 같은 docker 네트워크에서 `http://app:8081/actuator/prometheus` 를 수집한다. PromQL 예:

```promql
rate(crawl_duration_seconds_sum[1h]) / rate(crawl_duration_seconds_count[1h])      # 크롤링 평균 소요 시간
sum by (stage, reason) (increase(crawl_errors_total[1d]))                          # 하루 크롤링 실패
sum by (result) (increase(notification_delivery_seconds_count[1d]))                # 하루 발송 결과
```

Prometheus 없이도 위의 `curl` 결과를 하루 한 번 파일로 남겨 두면 주간 비교가 된다.

```bash
docker compose exec -T app curl -s localhost:8081/actuator/prometheus \
  | grep -E "^(crawl|notification|retention)_" > metrics/$(date +%F).txt
```

## 4. 배포 (재시작) 확인

새 버전을 올릴 때 graceful shutdown 이 동작하는지 함께 확인한다.

```bash
git pull
time docker compose up -d --build app      # 기존 컨테이너 SIGTERM → 새 컨테이너 시작
docker compose logs app --since 5m | grep -E "종료|shutdown|Started"
```

- 이전 컨테이너가 60초(`stop_grace_period`) 안에 끝나야 한다. 강제 종료(exit 137)면 로그를 확인한다.
- 재시작 후 **첫 주기가 바로 크롤링하는지** 본다. ShedLock 락이 남아 있으면 최대 10분 동안 건너뛴다 (PR #18 에서 고친 문제).

```bash
docker compose exec db mysql -u root -p moviebotdb -e "select name, lock_until, locked_at, locked_by from shedlock;"
```

## 5. 백업

```bash
docker compose exec -T db mysqldump -u root -p"$DB_ROOT_PASSWORD" moviebotdb | gzip > backup/$(date +%F).sql.gz
```

`.env` 를 `source` 하지 않았다면 비밀번호는 직접 입력한다. 백업 파일과 `.env` 는 레포에 올리지 않는다.

## 6. 장애 대응 메모

| 증상 | 먼저 볼 것 |
|---|---|
| 알림이 안 온다 | `crawl_errors_total`, `notification_outbox_messages{status="PENDING"}`, 봇 토큰 |
| `crawl_errors_total{reason="http_403"}` 급증 | CGV 차단. 주기를 늘리고 며칠 지켜본다 |
| 컨테이너가 `unhealthy` | `docker compose logs app`, DB 연결 (`/actuator/health` 의 `db`) |
| 디스크 증가 | `retention_deleted_total` 이 매일 늘어나는지, MySQL 볼륨 크기 |

## 7. 주간 운영 기록 (포트폴리오용)

매주 한 번 아래 표를 채운다. 3~4주치가 모이면 README 와 노션에 "운영 결과"로 붙인다.
**측정 기간과 규모(사용자 수, 신청 수)를 꼭 함께 적는다.** 작은 규모라도 실제 숫자가 "운영해 봤다"는 근거가 된다.

| 주차 (기간) | 사용자 / 대기 신청 | 크롤링 횟수 | 크롤링 실패 (원인) | 주기 시간 평균 / 최대 | 발송 성공 / 재시도 / 포기 | 알림 지연 (오픈 → 수신) | 배포 횟수 / 재시작 후 첫 주기 | 특이 사항 |
|---|---|---|---|---|---|---|---|---|
| 1주 (MM/DD~MM/DD) | | | | | | | | |
| 2주 | | | | | | | | |
| 3주 | | | | | | | | |
| 4주 | | | | | | | | |

모을 만한 이야기:

- **실제로 겪은 장애**: 차단, 브라우저 오류, 텔레그램 429. 무엇을 보고 알았고 어떻게 대응했는지 적는다. 지표가 없었다면 몰랐을 것이라는 점이 모니터링 작업의 근거가 된다.
- **알림 지연**: 예매가 열린 시각과 알림을 받은 시각의 차이 (주기 5분 + 크롤링 시간).
- **재배포 시 공백**: 재시작 후 첫 주기까지 걸린 시간.
