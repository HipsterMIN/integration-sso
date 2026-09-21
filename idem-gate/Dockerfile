# ════════════════════════════════════════════════════════════════════════════
# Q-Sign — Dockerfile (최적화 v2.0)
# S8-T6: Docker 멀티스테이지 빌드 최적화
#
# 주요 개선사항:
#   1. Gradle 빌드 캐시 마운트 (BuildKit --mount=type=cache)
#   2. Spring Boot Layered JAR → 레이어별 COPY
#   3. OCI 이미지 레이블 표준
#   4. HEALTHCHECK 내장
#   5. 빌드 ARG (APP_VERSION, GIT_SHA) 주입 지원
#
# 빌드:
#   DOCKER_BUILDKIT=1 docker build -t idem-gate:latest -f idem-gate/Dockerfile .
# ════════════════════════════════════════════════════════════════════════════

# ── 빌드 인수 ────────────────────────────────────────────────────────────────
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
# (목표 모듈 외의 src 는 Stage 2 에서 복사하지 않으므로 컨텍스트 크기는 최소화됨.)
COPY idem-common/build.gradle.kts idem-common/
COPY idem-gate/build.gradle.kts          idem-gate/
COPY idem-registry/build.gradle.kts            idem-registry/
COPY idem-hub/build.gradle.kts             idem-hub/
COPY idem-authz/build.gradle.kts          idem-authz/
COPY idem-console/build.gradle.kts      idem-console/
COPY idem-tenant-sample/build.gradle.kts     idem-tenant-sample/
COPY idem-sdk-java/build.gradle.kts idem-sdk-java/
COPY idem-relay/build.gradle.kts idem-relay/
COPY idem-agent/build.gradle.kts   idem-agent/
COPY plugins/idem-plugin-mock-auth/build.gradle.kts plugins/idem-plugin-mock-auth/
COPY plugins/idem-plugin-nice-oacx/build.gradle.kts plugins/idem-plugin-nice-oacx/
COPY plugins/idem-plugin-anyid/build.gradle.kts plugins/idem-plugin-anyid/

RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew && \
    ./gradlew :idem-gate:dependencies --no-daemon --quiet || true

# ── Stage 2: Build ────────────────────────────────────────────────────────────
FROM deps AS builder

COPY idem-common/src/ idem-common/src/
COPY idem-gate/src/ idem-gate/src/

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :idem-gate:bootJar \
        -x test \
        --no-daemon \
        --parallel \
        --build-cache && \
    mkdir -p /workspace/layers && \
    java -Djarmode=layertools \
         -jar /workspace/idem-gate/build/libs/idem-gate-*.jar \
         extract --destination /workspace/layers

# ── Stage 3: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:${JAVA_VERSION}-jre-alpine AS runtime

ARG APP_VERSION
ARG GIT_SHA
ARG BUILD_DATE
LABEL org.opencontainers.image.title="Idem Platform — Q-Sign Service" \
      org.opencontainers.image.description="Q-Sign (Digital Signature) — 전자서명 처리 및 OACX 연동 서비스" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${GIT_SHA}" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.source="https://github.com/HipsterMIN/integration-sso" \
      org.opencontainers.image.vendor="Idem" \
      org.opencontainers.image.licenses="Proprietary"

WORKDIR /app

RUN addgroup -S qsign && \
    adduser -S qsign -G qsign -h /app -s /sbin/nologin && \
    apk add --no-cache curl

# Spring Boot Layered JAR 레이어 순서 복사
COPY --from=builder --chown=qsign:qsign /workspace/layers/dependencies/          ./
COPY --from=builder --chown=qsign:qsign /workspace/layers/spring-boot-loader/    ./
COPY --from=builder --chown=qsign:qsign /workspace/layers/snapshot-dependencies/ ./
COPY --from=builder --chown=qsign:qsign /workspace/layers/application/           ./

USER qsign

EXPOSE 8081

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
    CMD curl -sf http://localhost:8081/actuator/health | \
        grep -q '"status":"UP"' || exit 1
