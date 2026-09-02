# ── 1단계: 빌드 ──────────────────────────────────────────────
FROM eclipse-temurin:17-jdk-jammy AS builder
WORKDIR /app

COPY gradlew ./
COPY gradle/ gradle/
RUN chmod +x gradlew

COPY build.gradle settings.gradle ./
RUN ./gradlew dependencies --no-daemon || true

COPY src/ src/
RUN ./gradlew bootJar --no-daemon -x test

# ── 2단계: 실행 ──────────────────────────────────────────────
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

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
    rm -rf /tmp/pw

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]