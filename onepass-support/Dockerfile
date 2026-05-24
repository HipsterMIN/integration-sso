# OnePass Support Dockerfile.
#
# Build:
#   DOCKER_BUILDKIT=1 docker build -f onepass-support/Dockerfile -t onepass-support:latest .

ARG JAVA_VERSION=21
ARG APP_VERSION=latest
ARG GIT_SHA=unknown
ARG BUILD_DATE=unknown

FROM eclipse-temurin:${JAVA_VERSION}-jdk-alpine AS deps
WORKDIR /workspace

ENV GRADLE_USER_HOME=/root/.gradle
ENV GRADLE_OPTS="-Dorg.gradle.daemon=false -Dorg.gradle.parallel=true"

COPY gradlew .
COPY gradle/ gradle/
COPY settings.gradle.kts .
COPY build.gradle.kts .
COPY platform-common/build.gradle.kts platform-common/
COPY q-sign/build.gradle.kts q-sign/
COPY q-im/build.gradle.kts q-im/
COPY ido/build.gradle.kts ido/
COPY onepass-support/build.gradle.kts onepass-support/
COPY onepass-fe/build.gradle.kts onepass-fe/
COPY agency-stub/build.gradle.kts agency-stub/
COPY onepass-agency-sdk/build.gradle.kts onepass-agency-sdk/
COPY outbox-relay-batch/build.gradle.kts outbox-relay-batch/
COPY onepass-agent/build.gradle.kts onepass-agent/

RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew && \
    ./gradlew :onepass-support:dependencies --no-daemon --quiet || true

FROM deps AS builder

COPY platform-common/src/ platform-common/src/
COPY onepass-support/src/ onepass-support/src/

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :onepass-support:bootJar \
        -x test \
        --no-daemon \
        --parallel \
        --build-cache && \
    mkdir -p /workspace/layers && \
    java -Djarmode=layertools \
         -jar /workspace/onepass-support/build/libs/onepass-support-*.jar \
         extract --destination /workspace/layers

FROM eclipse-temurin:${JAVA_VERSION}-jre-alpine AS runtime

ARG APP_VERSION
ARG GIT_SHA
ARG BUILD_DATE
LABEL org.opencontainers.image.title="OnePass Support Service" \
      org.opencontainers.image.description="OnePass Support CS backoffice and public Q&A/FAQ API" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${GIT_SHA}" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.source="https://github.com/HipsterMIN/integration-sso" \
      org.opencontainers.image.vendor="SMES" \
      org.opencontainers.image.licenses="Proprietary"

WORKDIR /app

RUN addgroup -S support && \
    adduser -S support -G support -h /app -s /sbin/nologin && \
    apk add --no-cache wget

COPY --from=builder --chown=support:support /workspace/layers/dependencies/ ./
COPY --from=builder --chown=support:support /workspace/layers/spring-boot-loader/ ./
COPY --from=builder --chown=support:support /workspace/layers/snapshot-dependencies/ ./
COPY --from=builder --chown=support:support /workspace/layers/application/ ./

USER support

EXPOSE 8085

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

HEALTHCHECK --interval=15s \
            --timeout=5s \
            --start-period=45s \
            --retries=5 \
    CMD wget -qO- http://localhost:8085/actuator/health | \
        grep -q '"status":"UP"' || exit 1
