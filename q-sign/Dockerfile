# ============================================================
# Q-Sign — Dockerfile
# 멀티스테이지 빌드: Gradle 빌드 → JRE 런타임 이미지
# ============================================================

# ── Stage 1: Build ───────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

# Gradle Wrapper + 빌드 스크립트만 먼저 복사 (레이어 캐시 최적화)
COPY gradlew .
COPY gradle/ gradle/
COPY settings.gradle.kts .
COPY build.gradle.kts .

# 서브모듈 빌드 파일
COPY platform-common/build.gradle.kts platform-common/
COPY q-sign/build.gradle.kts q-sign/

# 소스 복사
COPY platform-common/src/ platform-common/src/
COPY q-sign/src/ q-sign/src/

RUN chmod +x gradlew && \
    ./gradlew :q-sign:bootJar -x test --no-daemon --parallel

# ── Stage 2: Runtime ─────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# 보안: non-root 사용자
RUN addgroup -S qsign && adduser -S qsign -G qsign
USER qsign

# 빌드 산출물 복사
COPY --from=builder /workspace/q-sign/build/libs/q-sign-*.jar app.jar

# Actuator health probe 포트
EXPOSE 8081

# JVM 최적화 옵션
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 \
               -Djava.security.egd=file:/dev/./urandom \
               -Dfile.encoding=UTF-8 -Duser.timezone=UTC"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
