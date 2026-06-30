# ════════════════════════════════════════════════════════════════════════════
# Q-AuthZ — Dockerfile (q-sign 패턴 복제)
# 연합 인가(Federated Authorization) — 역할 부여 SoR + SCIM 2.0 Groups (포트 8086)
#
# 빌드:
#   DOCKER_BUILDKIT=1 docker build -t q-authz:latest -f q-authz/Dockerfile .
# ════════════════════════════════════════════════════════════════════════════

ARG JAVA_VERSION=21
ARG APP_VERSION=latest
ARG GIT_SHA=unknown
ARG BUILD_DATE=unknown

# ── Stage 1: Dependency Layer ────────────────────────────────────────────────
FROM eclipse-temurin:${JAVA_VERSION}-jdk-alpine AS deps
WORKDIR /workspace

ENV GRADLE_USER_HOME=/root/.gradle
ENV GRADLE_OPTS="-Dorg.gradle.daemon=false -Dorg.gradle.parallel=true"

COPY gradlew .
COPY gradle/ gradle/
COPY settings.gradle.kts .
COPY build.gradle.kts .
# settings.gradle.kts include() 한 모든 모듈의 빌드 스크립트 — Gradle 설정 평가에 필요.
COPY platform-common/build.gradle.kts platform-common/
COPY q-sign/build.gradle.kts          q-sign/
COPY q-im/build.gradle.kts            q-im/
COPY q-authz/build.gradle.kts         q-authz/
COPY ido/build.gradle.kts             ido/
COPY onepass-fe/build.gradle.kts      onepass-fe/
COPY agency-stub/build.gradle.kts     agency-stub/
COPY onepass-agency-sdk/build.gradle.kts onepass-agency-sdk/
COPY outbox-relay-batch/build.gradle.kts outbox-relay-batch/
COPY onepass-agent/build.gradle.kts   onepass-agent/

RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew && \
    ./gradlew :q-authz:dependencies --no-daemon --quiet || true

# ── Stage 2: Build ────────────────────────────────────────────────────────────
FROM deps AS builder

COPY platform-common/src/ platform-common/src/
COPY q-authz/src/ q-authz/src/

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :q-authz:bootJar \
        -x test \
        --no-daemon \
        --parallel \
        --build-cache && \
    mkdir -p /workspace/layers && \
    java -Djarmode=layertools \
         -jar /workspace/q-authz/build/libs/q-authz-*.jar \
         extract --destination /workspace/layers

# ── Stage 3: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:${JAVA_VERSION}-jre-alpine AS runtime

ARG APP_VERSION
ARG GIT_SHA
ARG BUILD_DATE
LABEL org.opencontainers.image.title="OnePass Platform — Q-AuthZ Service" \
      org.opencontainers.image.description="Q-AuthZ (Federated Authorization) — 역할 부여 SoR · SCIM 2.0 Groups · 한시 권한 만료" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${GIT_SHA}" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.source="https://github.com/HipsterMIN/integration-sso" \
      org.opencontainers.image.vendor="SMES" \
      org.opencontainers.image.licenses="Proprietary"

WORKDIR /app

RUN addgroup -S qauthz && \
    adduser -S qauthz -G qauthz -h /app -s /sbin/nologin && \
    apk add --no-cache curl

# Spring Boot Layered JAR 레이어 순서 복사
COPY --from=builder --chown=qauthz:qauthz /workspace/layers/dependencies/          ./
COPY --from=builder --chown=qauthz:qauthz /workspace/layers/spring-boot-loader/    ./
COPY --from=builder --chown=qauthz:qauthz /workspace/layers/snapshot-dependencies/ ./
COPY --from=builder --chown=qauthz:qauthz /workspace/layers/application/           ./

USER qauthz

EXPOSE 8086

ENV JAVA_OPTS="\
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=200 \
    -XX:MaxRAMPercentage=75.0 \
    -XX:InitialRAMPercentage=50.0 \
    -XX:+ExitOnOutOfMemoryError \
    -Djava.security.egd=file:/dev/./urandom \
    -Dfile.encoding=UTF-8 \
    -Duser.timezone=Asia/Seoul \
    -Dspring.backgroundpreinitializer.ignore=true"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]

HEALTHCHECK --interval=10s \
            --timeout=5s \
            --start-period=30s \
            --retries=3 \
    CMD curl -sf http://localhost:8086/actuator/health | \
        grep -q '"status":"UP"' || exit 1
