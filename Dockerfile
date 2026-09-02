# ── 1단계: 빌드 ──────────────────────────────────────────────
FROM eclipse-temurin:17-jdk-jammy AS builder
WORKDIR /app

# gradlew 래퍼로 정확한 Gradle 버전(9.7.1) 사용
COPY gradlew ./
COPY gradle/ gradle/
RUN chmod +x gradlew

# 의존성 캐시 레이어 (소스 변경 시 재다운로드 방지)
COPY build.gradle settings.gradle ./
RUN ./gradlew dependencies --no-daemon || true

COPY src/ src/
RUN ./gradlew bootJar --no-daemon -x test

# ── 2단계: 실행 ──────────────────────────────────────────────
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

COPY --from=builder /app/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]