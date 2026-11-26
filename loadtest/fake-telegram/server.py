"""
부하 테스트용 가짜 텔레그램 Bot API (표준 라이브러리만 사용).

실제 텔레그램처럼 봇 전체 발송량이 초당 LIMIT_PER_SEC 를 넘으면
429 {"parameters": {"retry_after": RETRY_AFTER}} 로 거절한다.
/metrics 로 받은 요청 수를 Prometheus 형식으로 보여 준다.

환경 변수: LIMIT_PER_SEC(30), RETRY_AFTER(5), LATENCY_MS(50), PORT(8080)
"""
import json
import os
import threading
import time
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

LIMIT_PER_SEC = int(os.environ.get("LIMIT_PER_SEC", "30"))
RETRY_AFTER = int(os.environ.get("RETRY_AFTER", "5"))
LATENCY_MS = int(os.environ.get("LATENCY_MS", "50"))
PORT = int(os.environ.get("PORT", "8080"))

lock = threading.Lock()
recent = deque()          # 최근 1초 동안 받아들인 sendMessage 시각
counts = {"ok": 0, "rate_limited": 0}
message_id = 0


def accept_send():
    """초당 한도 안이면 True. 넘으면 False (429)"""
    global message_id
    now = time.monotonic()
    with lock:
        while recent and now - recent[0] >= 1.0:
            recent.popleft()
        if len(recent) >= LIMIT_PER_SEC:
            counts["rate_limited"] += 1
            return None
        recent.append(now)
        counts["ok"] += 1
        message_id += 1
        return message_id


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):   # 요청마다 로그를 남기지 않음
        pass

    def reply(self, status, body, content_type="application/json"):
        data = body.encode() if isinstance(body, str) else json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def read_body(self):
        if self.headers.get("Transfer-Encoding", "").lower() == "chunked":   # Java HTTP 클라이언트가 보내는 방식
            while True:
                size = int(self.rfile.readline().split(b";")[0].strip() or b"0", 16)
                if size == 0:
                    self.rfile.readline()
                    return
                self.rfile.read(size)
                self.rfile.readline()
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)

    def handle_api(self):
        path = urlparse(self.path).path
        self.read_body()   # 본문을 다 읽어야 keep-alive 연결이 꼬이지 않는다
        method = path.rsplit("/", 1)[-1]

        if path == "/metrics":
            with lock:
                body = (
                    "# TYPE fake_telegram_send_total counter\n"
                    f'fake_telegram_send_total{{result="ok"}} {counts["ok"]}\n'
                    f'fake_telegram_send_total{{result="rate_limited"}} {counts["rate_limited"]}\n'
                )
            return self.reply(200, body, "text/plain; version=0.0.4")
        if not path.startswith("/bot"):
            return self.reply(404, {"ok": False, "error_code": 404, "description": "Not Found"})

        if method == "sendMessage":
            time.sleep(LATENCY_MS / 1000)
            mid = accept_send()
            if mid is None:
                return self.reply(429, {
                    "ok": False, "error_code": 429,
                    "description": f"Too Many Requests: retry after {RETRY_AFTER}",
                    "parameters": {"retry_after": RETRY_AFTER},
                })
            return self.reply(200, {"ok": True, "result": {"message_id": mid}})
        if method == "getUpdates":
            time.sleep(1)   # long polling(timeout=1) 흉내
            return self.reply(200, {"ok": True, "result": []})
        if method == "setMyCommands":
            return self.reply(200, {"ok": True, "result": True})
        return self.reply(200, {"ok": True, "result": True})

    do_GET = handle_api
    do_POST = handle_api


if __name__ == "__main__":
    print(f"fake telegram on :{PORT} limit={LIMIT_PER_SEC}/s retry_after={RETRY_AFTER}s latency={LATENCY_MS}ms", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
