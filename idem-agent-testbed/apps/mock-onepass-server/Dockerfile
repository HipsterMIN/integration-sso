# ─────────────────────────────────────────────────────────────────────────────
# Mock OnePass Server — Dockerfile
#
# 순수 JDK(com.sun.net.httpserver)만 사용하므로 별도 빌드 도구 불필요.
# 단일 스테이지 빌드: JDK 11로 컴파일 → 실행
# ─────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:11-jdk-jammy AS build

WORKDIR /build

# Java 소스 복사
COPY src/main/java/io/github/hipstermin/idem/testbed/mock/MockOnePassServer.java \
     /build/io/github/hipstermin/idem/testbed/mock/MockOnePassServer.java

# 컴파일 (외부 의존 없음 — JDK 표준 라이브러리만 사용)
RUN javac -source 11 -target 11 \
    -d /build/classes \
    /build/io/github/hipstermin/idem/testbed/mock/MockOnePassServer.java

# manifest 생성
RUN echo "Main-Class: io.github.hipstermin.idem.testbed.mock.MockOnePassServer" > /build/MANIFEST.MF

# JAR 패키징
RUN jar cfm /build/mock-onepass-server.jar /build/MANIFEST.MF \
    -C /build/classes .

# ─────────────────────────────────────────────────────────────────────────────
# 실행 이미지 (JRE만으로 충분)
# ─────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:11-jre-jammy

LABEL maintainer="OnePass Team <onepass@smes.go.kr>"
LABEL description="Mock OnePass SSO Server for Agent Testbed"

# curl 설치 (헬스체크용)
RUN apt-get update -qq && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=build /build/mock-onepass-server.jar /app/mock-onepass-server.jar

# 기본 환경변수 (docker-compose.yml에서 오버라이드 가능)
ENV SERVER_PORT=8080 \
    VALID_TOKENS="test-token-001,test-token-002,admin-token" \
    RESPONSE_DELAY_MS=0 \
    API_KEY=""

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=5s --retries=5 \
    CMD curl -sf http://localhost:${SERVER_PORT}/health || exit 1

# JVM 설정: 최소 힙 (경량 서버)
ENTRYPOINT ["java", \
    "-Xms32m", "-Xmx64m", \
    "-XX:+UseSerialGC", \
    "-Djava.net.preferIPv4Stack=true", \
    "-jar", "/app/mock-onepass-server.jar"]
