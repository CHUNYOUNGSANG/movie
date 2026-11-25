# ── 1단계: 빌드 ──────────────────────────────────────────────
FROM eclipse-temurin:17-jdk-jammy AS builder
WORKDIR /app

COPY gradlew ./
COPY gradle/ gradle/
RUN chmod +x gradlew

# lombok.config 도 함께: @RequiredArgsConstructor 가 필드의 @Qualifier·@Value 를 생성자에 복사하는 설정 (없으면 앱이 시작되지 않음)
COPY build.gradle settings.gradle lombok.config ./
RUN ./gradlew dependencies --no-daemon || true

COPY src/ src/
RUN ./gradlew bootJar --no-daemon -x test

# ── 2단계: 실행 ──────────────────────────────────────────────
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# root 가 아닌 전용 사용자로 실행한다 (브라우저가 외부 사이트를 여는 프로세스이므로 컨테이너 탈출 시 피해를 줄임)
RUN groupadd --system app && useradd --system --gid app --home-dir /app --shell /usr/sbin/nologin app

# Chromium 시스템 의존 라이브러리 + unzip
RUN apt-get update && apt-get install -y --no-install-recommends \
    libglib2.0-0 libnss3 libnspr4 libatk1.0-0 libatk-bridge2.0-0 \
    libcups2 libdrm2 libxcb1 libxkbcommon0 libx11-6 libxcomposite1 \
    libxdamage1 libxext6 libxfixes3 libxrandr2 libgbm1 libpango-1.0-0 \
    libcairo2 libasound2 libexpat1 fonts-liberation unzip \
    && rm -rf /var/lib/apt/lists/*

COPY --from=builder /app/build/libs/*.jar app.jar

# fat jar에서 의존성 추출 후 Playwright Chromium 설치
# PLAYWRIGHT_BROWSERS_PATH는 docker-compose volume 경로와 맞춰야 함
ENV PLAYWRIGHT_BROWSERS_PATH=/playwright-browsers
RUN unzip -q app.jar 'BOOT-INF/lib/*' -d /tmp/pw && \
    java -cp "/tmp/pw/BOOT-INF/lib/*" com.microsoft.playwright.CLI install --with-deps chromium && \
    rm -rf /tmp/pw && \
    chown -R app:app /app /playwright-browsers

USER app

EXPOSE 8080

# 컨테이너가 떠 있어도 앱이 응답하지 않으면 unhealthy (관리 포트 8081 의 /actuator/health: DB 연결 포함)
# start-period: Flyway 마이그레이션·Playwright 준비 시간
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fsS http://localhost:8081/actuator/health || exit 1

# exec 형식: java 가 PID 1 이 되어 docker stop 의 SIGTERM 을 직접 받는다 (정상 종료)
ENTRYPOINT ["java", "-jar", "app.jar"]