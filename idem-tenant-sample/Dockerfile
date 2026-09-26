# ============================================================
# Agency-Stub — Dockerfile
# 유관기관 OIDC 클라이언트 시뮬레이터
# 멀티스테이지 빌드: Gradle 빌드 → JRE 런타임 이미지
#
# 빌드 명령 (프로젝트 루트에서):
#   ./gradlew :idem-tenant-sample:bootJar -x test
#   docker build -f idem-tenant-sample/Dockerfile -t idem-tenant-sample:latest .
# ============================================================

# ── Stage 1: Build ───────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

# Gradle Wrapper + 빌드 스크립트 먼저 복사 (레이어 캐시 최적화)
COPY gradlew .
COPY gradle/ gradle/
COPY settings.gradle.kts .
COPY build.gradle.kts .

# settings.gradle.kts include() 한 모든 모듈의 빌드 스크립트 — Gradle 설정 평가에 필요.
COPY idem-common/build.gradle.kts idem-common/
COPY idem-gate/build.gradle.kts          idem-gate/
COPY idem-registry/build.gradle.kts            idem-registry/
COPY idem-hub/build.gradle.kts             idem-hub/
COPY idem-authz/build.gradle.kts          idem-authz/
COPY editions/idem-kr-portal/build.gradle.kts editions/idem-kr-portal/
COPY idem-tenant-sample/build.gradle.kts     idem-tenant-sample/
COPY idem-sdk-java/build.gradle.kts idem-sdk-java/
COPY idem-relay/build.gradle.kts idem-relay/
COPY idem-agent/build.gradle.kts   idem-agent/
COPY plugins/idem-plugin-mock-auth/build.gradle.kts plugins/idem-plugin-mock-auth/
COPY plugins/idem-plugin-nice-oacx/build.gradle.kts plugins/idem-plugin-nice-oacx/
COPY plugins/idem-plugin-anyid/build.gradle.kts plugins/idem-plugin-anyid/
COPY editions/idem-kr-hub/build.gradle.kts editions/idem-kr-hub/
COPY editions/idem-kr-registry/build.gradle.kts editions/idem-kr-registry/

# 소스 복사 (목표 모듈 + 의존 idem-common 만)
COPY idem-common/src/ idem-common/src/
COPY idem-tenant-sample/src/ idem-tenant-sample/src/

RUN chmod +x gradlew && \
    ./gradlew :idem-tenant-sample:bootJar -x test --no-daemon --parallel

# ── Stage 2: Runtime ─────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# 보안: non-root 사용자
RUN addgroup -S -g 1001 agency && adduser -S -u 1001 agency -G agency   # 1.0.1: 숫자 UID (K8s runAsNonRoot)
USER 1001:1001

# 빌드 산출물 복사
COPY --from=builder /workspace/idem-tenant-sample/build/libs/idem-tenant-sample-*.jar app.jar

# Actuator health probe 포트
EXPOSE 8084

# JVM 최적화 옵션
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 \
               -Djava.security.egd=file:/dev/./urandom \
               -Dfile.encoding=UTF-8 -Duser.timezone=UTC"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
