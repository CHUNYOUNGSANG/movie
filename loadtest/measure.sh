#!/usr/bin/env bash
# 부하 테스트 한 번: 스택을 새로 띄우고 신청 USERS 건을 넣은 뒤 발송이 끝날 때까지 기다려 결과를 낸다.
# 사용법: loadtest/measure.sh <USERS> [MOVIES] [라벨]
#   예) loadtest/measure.sh 5000 50 before
# 앱 이미지는 APP_IMAGE 로 고른다 (기본 moviebot-loadtest-app:latest = 지금 코드)
# 앱 설정은 환경 변수로 바꾼다 (loadtest/README.md). 결과는 loadtest/results.csv 에 한 줄씩 쌓인다.
set -euo pipefail
cd "$(dirname "$0")/.."

USERS=${1:?USERS}
MOVIES=${2:-50}
LABEL=${3:-run}
TIMEOUT_SEC=${TIMEOUT_SEC:-7200}
COMPOSE="docker compose -f docker-compose.loadtest.yml"
sql() { $COMPOSE exec -T db mysql -uroot -ploadtest -N moviebotdb -e "$1" 2>/dev/null; }

# 앱·DB·가짜 텔레그램만 새로 띄운다 (DB 는 tmpfs 라 다시 만들면 비워짐). Prometheus·Grafana 는 그대로 두어 그래프가 이어진다
$COMPOSE rm -sf app db fake-telegram >/dev/null 2>&1 || true
$COMPOSE up -d --no-build >/dev/null 2>&1
for _ in $(seq 1 60); do
  [ "$(docker inspect -f '{{.State.Health.Status}}' moviebot-loadtest-app-1 2>/dev/null)" = healthy ] && break
  sleep 3
done

(echo "SET @users=$USERS, @movies=$MOVIES;"; cat loadtest/seed.sql) \
  | $COMPOSE exec -T db mysql -uroot -ploadtest moviebotdb >/dev/null 2>&1
echo "[$LABEL] 신청 $USERS 건 (영화 $MOVIES 편) 넣음 $(date +%T). 발송 완료를 기다린다"

start=$(date +%s)
while :; do
  done_cnt=$(sql "select count(*) from notification_outbox where status in ('SENT','DEAD','CANCELED')")
  [ "${done_cnt:-0}" -ge "$USERS" ] && break
  if [ $(( $(date +%s) - start )) -gt "$TIMEOUT_SEC" ]; then echo "[$LABEL] 시간 초과 (완료 $done_cnt / $USERS)"; break; fi
  sleep 5
done

# 알림 한 건의 발송 지연 = Outbox 기록(매칭 커밋) → 발송 완료. 크롤링 주기 대기는 빼고 잰다
read -r n total p50 p95 maxlat dead <<<"$(sql "
with lat as (
  select timestampdiff(microsecond, created_at, updated_at) / 1e6 as s, status,
         row_number() over (order by timestampdiff(microsecond, created_at, updated_at)) as rn,
         count(*) over () as cnt
  from notification_outbox where status in ('SENT','DEAD','CANCELED'))
select count(*),
       (select round(timestampdiff(microsecond, min(created_at), max(updated_at)) / 1e6, 1) from notification_outbox),
       round(max(case when rn = ceil(cnt * 0.50) then s end), 1),
       round(max(case when rn = ceil(cnt * 0.95) then s end), 1),
       round(max(s), 1),
       sum(status = 'DEAD')
from lat")"
rate429=$(curl -s --get http://127.0.0.1:9091/api/v1/query --data-urlencode 'query=fake_telegram_send_total{result="rate_limited"}' \
  | python3 -c "import json,sys;r=json.load(sys.stdin)['data']['result'];print(r[0]['value'][1] if r else 0)")
tput=$(python3 -c "print(round($n / $total, 1) if $total else 0)")

printf "[%s] 완료 %s건 | 전체 %ss | 초당 %s건 | 지연 p50 %ss p95 %ss 최대 %ss | DEAD %s | 429 %s\n" \
  "$LABEL" "$n" "$total" "$tput" "$p50" "$p95" "$maxlat" "$dead" "$rate429"
[ -f loadtest/results.csv ] || echo "label,users,movies,done,total_s,per_sec,p50_s,p95_s,max_s,dead,rejected_429,measured_at" > loadtest/results.csv
echo "$LABEL,$USERS,$MOVIES,$n,$total,$tput,$p50,$p95,$maxlat,$dead,$rate429,$(date '+%F %T')" >> loadtest/results.csv
