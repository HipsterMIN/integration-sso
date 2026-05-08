# ============================================================
# IdO — Dockerfile
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
COPY ido/build.gradle.kts ido/

COPY platform-common/src/ platform-common/src/
COPY ido/src/ ido/src/

RUN chmod +x gradlew && \
    ./gradlew :ido:bootJar -x test --no-daemon --parallel

# ── Stage 2: Runtime ─────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

RUN addgroup -S ido && adduser -S ido -G ido
USER ido

COPY --from=builder /workspace/ido/build/libs/ido-*.jar app.jar

EXPOSE 8083

ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 \
               -Djava.security.egd=file:/dev/./urandom \
               -Dfile.encoding=UTF-8 -Duser.timezone=UTC"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
