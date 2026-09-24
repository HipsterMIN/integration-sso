# ════════════════════════════════════════════════════════════════════════════
# Q-AuthZ — Dockerfile (idem-gate 패턴 복제)
# 연합 인가(Federated Authorization) — 역할 부여 SoR + SCIM 2.0 Groups (포트 8086)
#
# 빌드:
#   DOCKER_BUILDKIT=1 docker build -t idem-authz:latest -f idem-authz/Dockerfile .
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
COPY idem-common/build.gradle.kts idem-common/
COPY idem-gate/build.gradle.kts          idem-gate/
COPY idem-registry/build.gradle.kts            idem-registry/
COPY idem-authz/build.gradle.kts         idem-authz/
COPY idem-hub/build.gradle.kts             idem-hub/
COPY idem-console/build.gradle.kts      idem-console/
COPY idem-tenant-sample/build.gradle.kts     idem-tenant-sample/
COPY idem-sdk-java/build.gradle.kts idem-sdk-java/
COPY idem-relay/build.gradle.kts idem-relay/
COPY idem-agent/build.gradle.kts   idem-agent/
COPY plugins/idem-plugin-mock-auth/build.gradle.kts plugins/idem-plugin-mock-auth/
COPY plugins/idem-plugin-nice-oacx/build.gradle.kts plugins/idem-plugin-nice-oacx/
COPY plugins/idem-plugin-anyid/build.gradle.kts plugins/idem-plugin-anyid/
COPY editions/idem-kr-hub/build.gradle.kts editions/idem-kr-hub/
COPY editions/idem-kr-registry/build.gradle.kts editions/idem-kr-registry/

RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew && \
    ./gradlew :idem-authz:dependencies --no-daemon --quiet || true

# ── Stage 2: Build ────────────────────────────────────────────────────────────
FROM deps AS builder

COPY idem-common/src/ idem-common/src/
COPY idem-authz/src/ idem-authz/src/

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :idem-authz:bootJar \
        -x test \
        --no-daemon \
        --parallel \
        --build-cache && \
    mkdir -p /workspace/layers && \
    java -Djarmode=layertools \
         -jar /workspace/idem-authz/build/libs/idem-authz-*.jar \
         extract --destination /workspace/layers

# ── Stage 3: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:${JAVA_VERSION}-jre-alpine AS runtime

ARG APP_VERSION
ARG GIT_SHA
ARG BUILD_DATE
LABEL org.opencontainers.image.title="Idem Platform — Q-AuthZ Service" \
      org.opencontainers.image.description="Q-AuthZ (Federated Authorization) — 역할 부여 SoR · SCIM 2.0 Groups · 한시 권한 만료" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${GIT_SHA}" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.source="https://github.com/HipsterMIN/integration-sso" \
      org.opencontainers.image.vendor="Idem" \
      org.opencontainers.image.licenses="Apache-2.0"

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
