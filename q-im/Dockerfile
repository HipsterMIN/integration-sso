# ============================================================
# Q-IM — Dockerfile
# 멀티스테이지 빌드: Gradle 빌드 → JRE 런타임 이미지
# ============================================================

# ── Stage 1: Build ───────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

COPY gradlew .
COPY gradle/ gradle/
COPY settings.gradle.kts .
COPY build.gradle.kts .

COPY platform-common/build.gradle.kts platform-common/
COPY q-im/build.gradle.kts q-im/

COPY platform-common/src/ platform-common/src/
COPY q-im/src/ q-im/src/

RUN chmod +x gradlew && \
    ./gradlew :q-im:bootJar -x test --no-daemon --parallel

# ── Stage 2: Runtime ─────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

RUN addgroup -S qim && adduser -S qim -G qim
USER qim

COPY --from=builder /workspace/q-im/build/libs/q-im-*.jar app.jar

EXPOSE 8082

ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 \
               -Djava.security.egd=file:/dev/./urandom \
               -Dfile.encoding=UTF-8 -Duser.timezone=UTC"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
