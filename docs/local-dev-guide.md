# OnePass 통합인증 플랫폼 — 로컬 개발 환경 구동 가이드

> **문서 분류**: 개발자 운영 가이드  
> **버전**: v1.0.0  
> **최종 수정**: 2026-05-07  
> **대상 독자**: 백엔드 개발자, 프론트엔드 개발자, DevOps  
> **관련 모듈**: `q-sign`, `q-im`, `ido`, `onepass-fe`, `agency-stub`, `platform-common`

---

## 목차

1. [사전 요구사항](#1-사전-요구사항)
   - 1.1 [Windows 설정](#11-windows-설정)
   - 1.2 [macOS 설정](#12-macos-설정)
   - 1.3 [Linux 설정](#13-linux-설정)
2. [프로젝트 클론 및 디렉토리 구조](#2-프로젝트-클론-및-디렉토리-구조)
3. [Step 1 — 인프라 기동 (Docker Compose)](#3-step-1--인프라-기동-docker-compose)
4. [Step 2 — 인프라 기동 확인](#4-step-2--인프라-기동-확인)
5. [Step 3 — Java 백엔드 빌드](#5-step-3--java-백엔드-빌드)
6. [Step 4 — 백엔드 서비스 기동](#6-step-4--백엔드-서비스-기동)
7. [Step 5 — 프론트엔드 기동](#7-step-5--프론트엔드-기동)
8. [Step 6 — 전체 동작 확인](#8-step-6--전체-동작-확인)
9. [서비스 포트 및 접속 URL 정리](#9-서비스-포트-및-접속-url-정리)
10. [IDE 설정 가이드](#10-ide-설정-가이드)
11. [자주 발생하는 오류 및 해결 방법](#11-자주-발생하는-오류-및-해결-방법)
    - 11.1 [Docker 관련 오류](#111-docker-관련-오류)
    - 11.2 [PostgreSQL 관련 오류](#112-postgresql-관련-오류)
    - 11.3 [Kafka / Zookeeper 관련 오류](#113-kafka--zookeeper-관련-오류)
    - 11.4 [Redis 관련 오류](#114-redis-관련-오류)
    - 11.5 [Gradle / Java 빌드 오류](#115-gradle--java-빌드-오류)
    - 11.6 [Spring Boot 기동 오류](#116-spring-boot-기동-오류)
    - 11.7 [Flyway 마이그레이션 오류](#117-flyway-마이그레이션-오류)
    - 11.8 [프론트엔드 (Node.js / Yarn) 오류](#118-프론트엔드-nodejs--yarn-오류)
    - 11.9 [Keycloak 관련 오류 (v1.1.0 신규)](#119-keycloak-관련-오류)
    - 11.10 [Windows 전용 오류](#1110-windows-전용-오류)
    - 11.11 [macOS 전용 오류](#1111-macos-전용-오류)
12. [개발 Tips 및 유용한 명령어](#12-개발-tips-및-유용한-명령어)
13. [서비스 종료 방법](#13-서비스-종료-방법)

---

## 1. 사전 요구사항

### 전체 공통 필수 소프트웨어

| 소프트웨어 | 최소 버전 | 권장 버전 | 용도 |
|-----------|---------|---------|------|
| **JDK** | 21 | Eclipse Temurin 21 LTS | Spring Boot 백엔드 |
| **Docker** | 24.0 | 최신 안정 버전 | 인프라 컨테이너 |
| **Docker Compose** | v2.0 | v2.24 이상 | 인프라 오케스트레이션 |
| **Git** | 2.30 | 최신 안정 버전 | 소스 코드 관리 |
| **Node.js** | 20.0 LTS | 20.14.0 LTS | React 프론트엔드 빌드 |
| **Yarn** | 1.22 | 1.22.22 | 패키지 매니저 |

> ⚠️ **중요**: JDK는 반드시 **21** 버전이어야 합니다. 17이나 11은 동작하지 않습니다.  
> ⚠️ **중요**: Gradle Wrapper가 포함되어 있으므로 **Gradle을 별도 설치할 필요가 없습니다.**  
> ⚠️ **중요**: Docker Compose는 **v2** 이상 (명령어: `docker compose`, 하이픈 없음)을 사용합니다.

---

### 1.1 Windows 설정

#### A. JDK 21 설치 (Eclipse Temurin 권장)

1. **공식 다운로드**: https://adoptium.net/temurin/releases/?version=21
2. **Windows x64 Installer (.msi)** 다운로드 후 실행
3. 설치 중 **"Add to PATH"**, **"Set JAVA_HOME"** 옵션 반드시 체크
4. 설치 완료 후 **PowerShell** (관리자 권한) 에서 확인:

```powershell
java -version
# 출력 예시:
# openjdk version "21.0.7" 2025-04-15 LTS
# OpenJDK Runtime Environment Temurin-21.0.7+6 (build 21.0.7+6-LTS)

echo $env:JAVA_HOME
# 출력 예시: C:\Program Files\Eclipse Adoptium\jdk-21.0.7.6-hotspot
```

5. JAVA_HOME이 비어 있으면 수동 설정:

```powershell
# 시스템 환경변수 등록 (관리자 PowerShell)
[System.Environment]::SetEnvironmentVariable(
  "JAVA_HOME",
  "C:\Program Files\Eclipse Adoptium\jdk-21.0.7.6-hotspot",
  "Machine"
)
# PATH에 추가
$path = [System.Environment]::GetEnvironmentVariable("Path", "Machine")
[System.Environment]::SetEnvironmentVariable(
  "Path",
  "$path;$env:JAVA_HOME\bin",
  "Machine"
)
```

6. PowerShell을 **새로 열고** 재확인:

```powershell
java -version
javac -version
```

#### B. Docker Desktop 설치

1. **공식 다운로드**: https://www.docker.com/products/docker-desktop/
2. Windows 요구사항 확인:
   - **WSL 2 방식 (권장)**: Windows 10 21H2 이상, Windows 11
   - **Hyper-V 방식**: Windows 10 Pro/Enterprise
3. Docker Desktop 설치 후 재부팅
4. **WSL 2 활성화** (아직 안 된 경우):

```powershell
# 관리자 PowerShell에서 실행
wsl --install
wsl --set-default-version 2
```

5. Docker Desktop 실행 후 시스템 트레이에서 **초록색 고래 아이콘** 확인
6. PowerShell에서 확인:

```powershell
docker --version
# Docker version 26.x.x

docker compose version
# Docker Compose version v2.x.x
```

#### C. Node.js 20 LTS 설치

1. **공식 다운로드**: https://nodejs.org/en/download (20.x LTS 선택)
2. Windows Installer (.msi) 실행
3. 설치 옵션 중 **"Add to PATH"** 체크
4. **PowerShell 재시작** 후 확인:

```powershell
node --version
# v20.x.x

npm --version
# 10.x.x
```

5. Yarn 설치:

```powershell
npm install -g yarn
yarn --version
# 1.22.x
```

#### D. Git 설치

1. **공식 다운로드**: https://git-scm.com/download/win
2. 설치 시 주요 옵션:
   - **Git Bash** 포함 선택 (권장 — Unix 명령어 사용 가능)
   - **"Use Git from the Windows Command Prompt"** 선택
   - **Line endings**: "Checkout Windows-style, commit Unix-style" 선택
3. 확인:

```powershell
git --version
# git version 2.x.x
```

#### E. Windows Terminal 설치 (권장)

Microsoft Store에서 **Windows Terminal** 설치 → PowerShell, Git Bash, WSL을 탭으로 관리 가능.

> 💡 **Windows 개발 팁**: 이후 모든 명령어는 **Git Bash** 또는 **WSL 터미널**에서 실행하는 것을 권장합니다. PowerShell도 가능하나 일부 Gradle 명령어는 `./gradlew` 대신 `.\gradlew.bat`으로 실행해야 합니다.

---

### 1.2 macOS 설정

#### A. Homebrew 설치 (패키지 매니저)

```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
```

설치 후 PATH 등록 (Apple Silicon M1/M2/M3의 경우):

```bash
echo 'eval "$(/opt/homebrew/bin/brew shellenv)"' >> ~/.zshrc
source ~/.zshrc
```

Intel Mac의 경우 `/usr/local/bin/brew`가 기본 PATH에 포함됩니다.

#### B. JDK 21 설치

```bash
# SDKMAN 방식 (권장 — 여러 JDK 버전 관리 가능)
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"

sdk install java 21.0.7-tem
sdk use java 21.0.7-tem
sdk default java 21.0.7-tem

# 확인
java -version
# openjdk version "21.0.7" 2025-04-15 LTS
```

또는 Homebrew 방식:

```bash
brew install --cask temurin@21
# 확인
java -version
```

JAVA_HOME 설정 (~/.zshrc 또는 ~/.bash_profile):

```bash
# Apple Silicon
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH=$JAVA_HOME/bin:$PATH
```

```bash
source ~/.zshrc   # 또는 source ~/.bash_profile
java -version
echo $JAVA_HOME
```

#### C. Docker Desktop 설치

1. **공식 다운로드**: https://www.docker.com/products/docker-desktop/
2. **Apple Silicon (M1/M2/M3)**: "Apple Silicon" 버전 다운로드
3. **Intel Mac**: "Intel Chip" 버전 다운로드
4. .dmg 파일 실행 → Applications 폴더로 드래그
5. Docker Desktop 실행 후 메뉴바 고래 아이콘 확인
6. 확인:

```bash
docker --version
docker compose version
```

> 💡 **macOS Docker 리소스 설정**: Docker Desktop → Settings → Resources  
> - Memory: 최소 **6GB** (권장 8GB 이상)  
> - CPUs: 최소 **4**  
> - Disk image size: 64GB 이상

#### D. Node.js 20 LTS 설치

```bash
# nvm 방식 (권장 — 버전 전환 가능)
curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.1/install.sh | bash
source ~/.zshrc   # 또는 ~/.bash_profile

nvm install 20
nvm use 20
nvm alias default 20

# 확인
node --version
# v20.x.x

# Yarn 설치
npm install -g yarn
yarn --version
# 1.22.x
```

또는 Homebrew 방식:

```bash
brew install node@20
echo 'export PATH="/opt/homebrew/opt/node@20/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc
```

#### E. Git 설치

macOS는 기본 Git이 설치되어 있으나 최신 버전 권장:

```bash
brew install git
git --version
```

---

### 1.3 Linux 설정

#### Ubuntu / Debian 계열

```bash
# 패키지 목록 업데이트
sudo apt-get update && sudo apt-get upgrade -y

# 기본 도구
sudo apt-get install -y curl wget git unzip
```

#### A. JDK 21 설치 (Eclipse Temurin)

```bash
# Adoptium GPG 키 및 저장소 등록
wget -O - https://packages.adoptium.net/artifactory/api/gpg/key/public | sudo apt-key add -
echo "deb https://packages.adoptium.net/artifactory/deb $(lsb_release -sc) main" \
  | sudo tee /etc/apt/sources.list.d/adoptium.list

sudo apt-get update
sudo apt-get install -y temurin-21-jdk

# 확인
java -version
javac -version

# JAVA_HOME 설정
echo 'export JAVA_HOME=/usr/lib/jvm/temurin-21-amd64' >> ~/.bashrc
echo 'export PATH=$JAVA_HOME/bin:$PATH' >> ~/.bashrc
source ~/.bashrc
```

CentOS / RHEL / Fedora 계열:

```bash
# RPM 패키지 설치 (Fedora 예시)
sudo dnf install -y java-21-openjdk-devel
# 또는 직접 다운로드
# https://adoptium.net/temurin/releases/?version=21 에서 RPM 다운로드

java -version
```

#### B. Docker 설치

```bash
# Docker 공식 설치 스크립트 (Ubuntu/Debian)
curl -fsSL https://get.docker.com -o get-docker.sh
sudo sh get-docker.sh

# 현재 사용자를 docker 그룹에 추가 (sudo 없이 docker 사용)
sudo usermod -aG docker $USER
newgrp docker

# Docker 서비스 시작 및 자동 시작 등록
sudo systemctl enable docker
sudo systemctl start docker

# 확인
docker --version
docker compose version
```

> ⚠️ `newgrp docker` 또는 **로그아웃 후 재로그인** 해야 그룹 권한이 적용됩니다.

#### C. Node.js 20 LTS 설치

```bash
# nvm 방식 (권장)
curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.1/install.sh | bash
source ~/.bashrc

nvm install 20
nvm use 20
nvm alias default 20

node --version
npm install -g yarn
yarn --version
```

또는 NodeSource 공식 저장소:

```bash
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt-get install -y nodejs
npm install -g yarn
```

---

## 2. 프로젝트 클론 및 디렉토리 구조

### 2.1 소스 코드 클론

```bash
git clone https://github.com/HipsterMIN/integration-sso.git
cd integration-sso
```

> ⚠️ **Windows Git Bash / WSL 사용자**: 클론 경로에 **한글, 공백, 특수문자**가 없는 경로를 권장합니다.  
> 예: `C:\dev\integration-sso` (권장) / `C:\Users\홍길동\문서\project` (비권장)

### 2.2 브랜치 확인

```bash
git branch -a
git checkout genspark_ai_developer   # 개발 브랜치
# 또는
git checkout main                    # 안정 브랜치
```

### 2.3 디렉토리 구조 확인

```
integration-sso/                      ← 프로젝트 루트
├── gradlew                           ← Linux/macOS Gradle Wrapper 실행파일
├── gradlew.bat                       ← Windows Gradle Wrapper 실행파일
├── build.gradle.kts                  ← 루트 Gradle 빌드 설정
├── settings.gradle.kts               ← 멀티프로젝트 모듈 등록
│
├── platform-common/                  ← 공통 도메인·이벤트·에러코드 (JAR)
├── q-sign/                           ← 인증 SoR (port 8081)
├── q-im/                             ← 식별 SoR (port 8082)
├── ido/                              ← 정책 오케스트레이터 + FE BFF (port 8083)
├── agency-stub/                      ← 기관 로컬 세션 시뮬레이터 (port 8084)
│
├── onepass-fe/                       ← 순수 React SPA (Node.js 모듈)
│   ├── build.gradle.kts
│   └── frontend/
│       ├── package.json
│       ├── webpack.config.js
│       └── src/
│
├── infra/
│   └── docker/
│       ├── docker-compose.yml        ← ★ 인프라 기동 파일
│       ├── init-db.sql               ← PostgreSQL 초기 스키마
│       ├── kafka/
│       │   └── create-topics.sh      ← Kafka 토픽 초기화
│       ├── nginx/
│       │   └── nginx.conf
│       ├── postgres/
│       │   ├── postgresql.conf
│       │   └── pg_hba.conf
│       └── redis/
│           └── redis.conf
│
└── docs/                             ← 설계 문서
```

### 2.4 Gradle Wrapper 실행 권한 설정 (Linux/macOS)

```bash
chmod +x gradlew
```

> ⚠️ **Windows**: `gradlew.bat`을 사용하거나, Git Bash에서 `./gradlew`로 실행합니다.  
> `gradlew.bat`은 CMD/PowerShell에서, `./gradlew`는 Git Bash/WSL에서 사용합니다.

---

## 3. Step 1 — 인프라 기동 (Docker Compose)

> 📋 **기동 순서**: 인프라(PostgreSQL → Redis → Zookeeper → Kafka) → 백엔드 → 프론트엔드  
> 인프라가 완전히 준비되지 않은 상태에서 Spring Boot를 기동하면 연결 오류가 발생합니다.

### 3.1 docker-compose.yml 위치

모든 Docker Compose 명령은 **프로젝트 루트**에서 아래 경로를 지정하여 실행합니다.

```
infra/docker/docker-compose.yml
```

### 3.2 기본 인프라 기동 (필수)

```bash
# 프로젝트 루트에서 실행
docker compose -f infra/docker/docker-compose.yml up -d
```

이 명령으로 기동되는 서비스:
- `onepass-postgres` — PostgreSQL 16 (port 5432)
- `onepass-redis` — Redis 7.2 (port 6379)
- `onepass-zookeeper` — Zookeeper (port 2181)
- `onepass-kafka` — Kafka Broker (port 9092)
- `onepass-kafka-init` — Kafka 토픽 초기화 (one-shot, 완료 후 종료)
- `onepass-kafka-ui` — Kafka UI 모니터링 (port 8090)
- `onepass-redis-insight` — Redis 데이터 뷰어 (port 5540)

#### Windows (PowerShell / CMD)

```powershell
# PowerShell 또는 CMD
docker compose -f infra/docker/docker-compose.yml up -d
```

#### Windows (Git Bash)

```bash
docker compose -f infra/docker/docker-compose.yml up -d
```

#### macOS / Linux

```bash
docker compose -f infra/docker/docker-compose.yml up -d
```

### 3.3 선택적 프로파일 기동

```bash
# pgAdmin 4 포함 기동 (DB GUI 관리 도구, port 5050)
docker compose -f infra/docker/docker-compose.yml --profile tools up -d

# Keycloak 포함 기동 (OIDC 브로커 모드 사용 시, port 8088)
docker compose -f infra/docker/docker-compose.yml --profile keycloak up -d

# Schema Registry 포함 기동 (Avro 스키마 관리, port 8085)
docker compose -f infra/docker/docker-compose.yml --profile schema up -d

# 전체 동시 기동 (인프라 + 모니터링 도구 + Keycloak)
docker compose -f infra/docker/docker-compose.yml \
  --profile tools \
  --profile keycloak \
  up -d
```

### 3.4 기동 로그 실시간 확인

```bash
# 전체 컨테이너 로그 실시간 출력
docker compose -f infra/docker/docker-compose.yml logs -f

# 특정 서비스만 확인
docker compose -f infra/docker/docker-compose.yml logs -f postgres
docker compose -f infra/docker/docker-compose.yml logs -f kafka
docker compose -f infra/docker/docker-compose.yml logs -f kafka-init
```

---

## 4. Step 2 — 인프라 기동 확인

> ⚠️ **반드시** 모든 인프라가 `healthy` 상태가 된 후 백엔드를 기동하세요.  
> Kafka는 기동에 **30~60초** 가 소요될 수 있습니다.

### 4.1 컨테이너 상태 확인

```bash
docker compose -f infra/docker/docker-compose.yml ps
```

**정상 상태 예시:**

```
NAME                    STATUS              PORTS
onepass-postgres        Up (healthy)        0.0.0.0:5432->5432/tcp
onepass-redis           Up (healthy)        0.0.0.0:6379->6379/tcp
onepass-zookeeper       Up (healthy)        0.0.0.0:2181->2181/tcp
onepass-kafka           Up (healthy)        0.0.0.0:9092->9092/tcp
onepass-kafka-init      Exited (0)                                     ← 0으로 종료 = 정상
onepass-kafka-ui        Up (healthy)        0.0.0.0:8090->8080/tcp
onepass-redis-insight   Up                  0.0.0.0:5540->5540/tcp
```

> ⚠️ `kafka-init`의 exit code가 **0** 이어야 정상입니다. **1** 이면 토픽 생성 실패입니다.

### 4.2 PostgreSQL 연결 확인

```bash
# Docker 컨테이너 내부에서 psql 실행
docker exec -it onepass-postgres psql -U onepass -d onepass -c "\dn"
```

**정상 출력:**

```
      List of schemas
    Name    |  Owner
------------+---------
 agency_stub | onepass
 ido         | onepass
 keycloak    | onepass
 public      | pg_database_owner
 qim         | onepass
 qsign       | onepass
(6 rows)
```

### 4.3 Redis 연결 확인

```bash
docker exec -it onepass-redis redis-cli ping
# PONG 이 출력되면 정상
```

### 4.4 Kafka 토픽 생성 확인

```bash
docker exec -it onepass-kafka \
  kafka-topics --bootstrap-server localhost:9092 --list
```

**정상 출력 (11개 토픽):**

```
ido.handoff.events
ido.handoff.events.dlq
platform.audit.log
platform.session.advisory
platform.session.advisory.dlq
qim.user.events
qim.user.events.dlq
qim.user.snapshot
qsign.auth.events
qsign.auth.events.dlq
```

> ⚠️ 토픽 목록이 비어 있거나 일부만 있는 경우 → [11.3 Kafka 오류 해결](#113-kafka--zookeeper-관련-오류) 참조

### 4.5 모니터링 UI 접속 확인

| 서비스 | URL | 계정 |
|--------|-----|------|
| Kafka UI | http://localhost:8090 | admin / admin |
| Redis Insight | http://localhost:5540 | 없음 |
| pgAdmin 4 | http://localhost:5050 | admin@onepass.local / admin |

---

## 5. Step 3 — Java 백엔드 빌드

### 5.1 전체 백엔드 빌드

인프라가 모두 `healthy` 상태인 것을 확인한 후 실행합니다.

#### Linux / macOS / Git Bash

```bash
# 프로젝트 루트에서 실행
# 테스트 제외 빌드 (권장 — 로컬 개발 시)
./gradlew :platform-common:build \
          :q-sign:build \
          :q-im:build \
          :ido:build \
          :agency-stub:build \
          -x test
```

#### Windows (PowerShell)

```powershell
.\gradlew.bat :platform-common:build `
              :q-sign:build `
              :q-im:build `
              :ido:build `
              :agency-stub:build `
              -x test
```

#### Windows (CMD)

```cmd
gradlew.bat :platform-common:build :q-sign:build :q-im:build :ido:build :agency-stub:build -x test
```

### 5.2 빌드 결과 확인

성공 시 각 모듈의 `build/libs/` 폴더에 JAR 파일이 생성됩니다:

```bash
ls -la q-sign/build/libs/
ls -la q-im/build/libs/
ls -la ido/build/libs/
ls -la agency-stub/build/libs/
```

**예상 출력:**

```
q-sign/build/libs/q-sign-0.1.0-SNAPSHOT.jar
q-im/build/libs/q-im-0.1.0-SNAPSHOT.jar
ido/build/libs/ido-0.1.0-SNAPSHOT.jar
agency-stub/build/libs/agency-stub-0.1.0-SNAPSHOT.jar
```

### 5.3 빌드 시 주의사항

```bash
# 첫 빌드 시 Gradle이 의존성을 다운로드하므로 수 분 소요됩니다.
# 이후 빌드는 캐시로 빠르게 완료됩니다.

# 빌드 실패 시 클린 빌드:
./gradlew clean build -x test

# 특정 모듈만 빌드:
./gradlew :ido:build -x test

# 빌드 디버그 출력 (오류 상세 확인):
./gradlew :ido:build -x test --stacktrace
```

---

## 6. Step 4 — 백엔드 서비스 기동

> 📋 **기동 순서 중요**: 서비스 간 의존성이 있으므로 아래 순서를 따릅니다.  
>
> ```
> 1. q-sign  (port 8081) — 인증 SoR, 의존성 없음
> 2. q-im    (port 8082) — 식별 SoR, 의존성 없음
> 3. ido     (port 8083) — q-sign, q-im 내부 호출 포함
> 4. agency-stub (port 8084) — 독립적
> ```

각 서비스는 **별도 터미널** 창에서 실행합니다. (4개 터미널 필요)

### 6.1 q-sign 기동 (터미널 1)

#### Linux / macOS / Git Bash

```bash
# 프로젝트 루트에서
./gradlew :q-sign:bootRun
```

#### Windows PowerShell

```powershell
.\gradlew.bat :q-sign:bootRun
```

**정상 기동 로그:**

```
  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
...
2026-05-07T10:00:01.000Z  INFO 1234 --- [q-sign] [main] k.g.s.qsign.QSignApplication   : Started QSignApplication in 4.512 seconds
```

**기동 확인:**

```bash
curl http://localhost:8081/actuator/health
# {"status":"UP"}
```

### 6.2 q-im 기동 (터미널 2)

#### Linux / macOS / Git Bash

```bash
./gradlew :q-im:bootRun
```

#### Windows PowerShell

```powershell
.\gradlew.bat :q-im:bootRun
```

**기동 확인:**

```bash
curl http://localhost:8082/actuator/health
# {"status":"UP"}
```

### 6.3 ido 기동 (터미널 3)

#### Linux / macOS / Git Bash

```bash
./gradlew :ido:bootRun
```

#### Windows PowerShell

```powershell
.\gradlew.bat :ido:bootRun
```

**기동 확인:**

```bash
curl http://localhost:8083/actuator/health
# {"status":"UP","components":{"db":{"status":"UP"},"kafka":{"status":"UP"},"redis":{"status":"UP"}}}
```

### 6.4 agency-stub 기동 (터미널 4)

#### Linux / macOS / Git Bash

```bash
./gradlew :agency-stub:bootRun
```

#### Windows PowerShell

```powershell
.\gradlew.bat :agency-stub:bootRun
```

**기동 확인:**

```bash
curl http://localhost:8084/actuator/health
# {"status":"UP"}
```

### 6.5 환경변수로 기동 (선택)

기본값이 이미 `application.yml`에 설정되어 있어 별도 환경변수 없이 로컬에서 바로 실행됩니다.  
환경을 변경해야 할 경우:

#### Linux / macOS

```bash
# 환경변수 재정의 예시
REDIS_HOST=localhost \
DB_HOST=localhost \
KAFKA_SERVERS=localhost:9092 \
./gradlew :ido:bootRun
```

#### Windows PowerShell

```powershell
$env:REDIS_HOST="localhost"
$env:DB_HOST="localhost"
$env:KAFKA_SERVERS="localhost:9092"
.\gradlew.bat :ido:bootRun
```

#### Windows Git Bash

```bash
REDIS_HOST=localhost DB_HOST=localhost ./gradlew :ido:bootRun
```

### 6.6 Spring Boot 브로커 모드 설정 (ido)

기본값은 `qsign` 모드입니다. Keycloak 모드로 전환 시:

```bash
# Keycloak 모드 (Keycloak 컨테이너 별도 기동 필요)
IDO_BROKER_MODE=keycloak ./gradlew :ido:bootRun
```

---

## 7. Step 5 — 프론트엔드 기동

프론트엔드는 **순수 React SPA**입니다. Spring Boot가 포함되어 있지 않습니다.  
React 개발 서버는 `port 3000`으로 실행되며, `/api/**` 요청을 자동으로 `ido(:8083)`로 프록시합니다.

### 7.1 방법 A: 직접 Yarn 사용 (권장)

**터미널 5 (새 터미널)**에서 실행합니다.

```bash
# onepass-fe/frontend 디렉토리로 이동
cd onepass-fe/frontend

# 의존성 설치 (최초 1회 또는 package.json 변경 시)
yarn install

# 개발 서버 기동 (port 3000)
yarn dev
```

#### Windows PowerShell

```powershell
cd onepass-fe\frontend
yarn install
yarn dev
```

**정상 기동 로그:**

```
<i> [webpack-dev-server] Project is running at:
<i> [webpack-dev-server] Loopback: http://localhost:3000/
<i> [webpack-dev-server] On Your Network (IPv4): http://192.168.x.x:3000/
webpack compiled successfully
```

브라우저에서 **http://localhost:3000** 접속.

### 7.2 방법 B: Gradle 태스크 사용

프로젝트 루트에서 실행합니다.

```bash
# 의존성 설치 + 개발 서버 기동 (한 번에)
./gradlew :onepass-fe:frontendDev
```

> 💡 `frontendDev` 태스크는 내부적으로 `yarn install` → `yarn dev`를 순서대로 실행합니다.

### 7.3 프론트엔드 프로덕션 빌드 (선택)

```bash
cd onepass-fe/frontend
yarn build:prod
# dist/ 폴더에 빌드 산출물 생성
```

---

## 8. Step 6 — 전체 동작 확인

### 8.1 전체 서비스 헬스체크 스크립트

아래 스크립트를 `check-health.sh` 로 저장하거나 직접 실행합니다.

#### Linux / macOS / Git Bash

```bash
#!/bin/bash
echo "=== OnePass 서비스 상태 확인 ==="

check() {
  local name=$1
  local url=$2
  if curl -sf "$url" > /dev/null 2>&1; then
    echo "✅ $name — OK ($url)"
  else
    echo "❌ $name — FAIL ($url)"
  fi
}

check "q-sign    (8081)" "http://localhost:8081/actuator/health"
check "q-im      (8082)" "http://localhost:8082/actuator/health"
check "ido       (8083)" "http://localhost:8083/actuator/health"
check "agency-stub(8084)" "http://localhost:8084/actuator/health"
check "React SPA (3000)" "http://localhost:3000"
check "Kafka UI  (8090)" "http://localhost:8090"
check "Redis Insight(5540)" "http://localhost:5540"
```

#### Windows PowerShell

```powershell
$services = @(
  @{Name="q-sign    (8081)"; Url="http://localhost:8081/actuator/health"},
  @{Name="q-im      (8082)"; Url="http://localhost:8082/actuator/health"},
  @{Name="ido       (8083)"; Url="http://localhost:8083/actuator/health"},
  @{Name="agency-stub(8084)"; Url="http://localhost:8084/actuator/health"},
  @{Name="React SPA (3000)"; Url="http://localhost:3000"},
  @{Name="Kafka UI  (8090)"; Url="http://localhost:8090"}
)

foreach ($s in $services) {
  try {
    Invoke-WebRequest -Uri $s.Url -UseBasicParsing -TimeoutSec 3 | Out-Null
    Write-Host "✅ $($s.Name) — OK"
  } catch {
    Write-Host "❌ $($s.Name) — FAIL"
  }
}
```

### 8.2 PoC 흐름 기본 테스트

```bash
# 1. q-sign — 인증 브로커 URL 생성 (KAKAO OIDC)
curl -v "http://localhost:8083/api/v1/broker/kakao/authorize?returnUrl=http://localhost:8084&requestedLevel=L1"
# 기대 결과: 302 Redirect → Kakao 인증 URL

# 2. q-im — 사용자 조회 (등록된 사용자가 없으면 404)
curl http://localhost:8082/api/v1/users/by-hash?identifierHash=test123

# 3. ido — FE 세션 상태 확인
curl http://localhost:8083/api/v1/fe-session/check \
  -H "Cookie: feSessionId=invalid"
# 기대 결과: 401 또는 세션 없음 응답

# 4. agency-stub — 기관 헬스체크
curl http://localhost:8084/actuator/health
```

---

## 9. 서비스 포트 및 접속 URL 정리

| 서비스 | URL | 계정 | 용도 |
|--------|-----|------|------|
| **React SPA (개발)** | http://localhost:3000 | — | 프론트엔드 HMR 개발서버 |
| **React SPA (Nginx)** | http://localhost:3001 | — | 프로덕션 Nginx 서빙 (Docker optionB) |
| **q-sign** | http://localhost:8081 | — | 인증 SoR API |
| **q-im** | http://localhost:8082 | — | 식별 SoR API |
| **ido (BFF)** | http://localhost:8083 | — | 정책 오케스트레이터 + FE BFF |
| **agency-stub** | http://localhost:8084 | — | 기관 로컬 세션 시뮬레이터 |
| **Keycloak** | http://localhost:8088 | admin / admin | OIDC 브로커 (profile: keycloak) |
| **Kafka UI** | http://localhost:8090 | admin / admin | Kafka 토픽/메시지 모니터링 |
| **Redis Insight** | http://localhost:5540 | — | Redis 키/데이터 뷰어 |
| **pgAdmin 4** | http://localhost:5050 | admin@onepass.local / admin | DB GUI (profile: tools) |
| **Schema Registry** | http://localhost:8085 | — | Avro 스키마 관리 (profile: schema) |
| **PostgreSQL** | localhost:5432 | onepass / onepass | DB 직접 접속 |
| **Redis** | localhost:6379 | 없음 | Redis 직접 접속 |
| **Kafka** | localhost:9092 | — | Kafka 외부 리스너 |
| **Zookeeper** | localhost:2181 | — | Zookeeper |

### PostgreSQL 스키마 구조

```
DB명: onepass
├── qsign.*        — Q-Sign 인증 SoR 테이블
├── qim.*          — Q-IM 식별 SoR 테이블
├── ido.*          — IdO 정책·Handoff·FE세션 테이블
├── agency_stub.*  — Agency-Stub 테이블
└── keycloak.*     — Keycloak 테이블 (keycloak 프로파일 사용 시)
```

---

## 10. IDE 설정 가이드

### 10.1 IntelliJ IDEA 설정 (권장)

#### A. 프로젝트 열기

1. **File → Open** → 프로젝트 루트 폴더 선택
2. "Open as Gradle Project" 클릭
3. **Gradle 동기화** 자동 실행 (수 분 소요)

#### B. JDK 설정

1. **File → Project Structure** (Ctrl+Alt+Shift+S / ⌘+;)
2. **Project → SDK**: `Eclipse Temurin 21` 선택
   - 없으면 **Add SDK → Download JDK** → `Eclipse Temurin 21` 다운로드
3. **Language level**: `21 - ...` 선택
4. **Apply → OK**

#### C. Gradle 설정

1. **File → Settings** (Ctrl+Alt+S) / **Preferences** (macOS)
2. **Build, Execution, Deployment → Build Tools → Gradle**
3. **Gradle JVM**: `Project SDK (21)` 선택
4. **Build and run using**: `Gradle` 선택
5. **Apply → OK**

#### D. Lombok 플러그인 설치

1. **File → Settings → Plugins**
2. `Lombok` 검색 → **Install**
3. IDE 재시작

#### E. 어노테이션 프로세서 활성화

1. **File → Settings → Build → Compiler → Annotation Processors**
2. **Enable annotation processing** 체크
3. **Apply**

#### F. 각 서비스 실행 설정 (Run Configuration)

1. **Run → Edit Configurations**
2. **+** 버튼 → **Gradle**
3. 각 서비스별 설정:

| Name | Gradle project | Tasks |
|------|--------------|-------|
| q-sign | `integration-sso:q-sign` | `bootRun` |
| q-im | `integration-sso:q-im` | `bootRun` |
| ido | `integration-sso:ido` | `bootRun` |
| agency-stub | `integration-sso:agency-stub` | `bootRun` |

---

### 10.2 VS Code 설정 (프론트엔드 개발)

#### A. 권장 확장 설치

```json
// .vscode/extensions.json 에 추가 가능
{
  "recommendations": [
    "dbaeumer.vscode-eslint",
    "esbenp.prettier-vscode",
    "bradlc.vscode-tailwindcss",
    "ms-vscode.vscode-typescript-next",
    "ms-azuretools.vscode-docker"
  ]
}
```

#### B. 백엔드 Java 개발 (VS Code)

확장 설치:
- **Extension Pack for Java** (Microsoft)
- **Spring Boot Extension Pack** (VMware)
- **Lombok Annotations Support for VS Code**

---

## 11. 자주 발생하는 오류 및 해결 방법

---

### 11.1 Docker 관련 오류

---

#### 오류: `Cannot connect to the Docker daemon`

**증상:**
```
ERROR: Cannot connect to the Docker daemon at unix:///var/run/docker.sock.
Is the docker daemon running?
```

**원인 및 해결:**

**Linux:**
```bash
# Docker 서비스 상태 확인
sudo systemctl status docker

# Docker 서비스 시작
sudo systemctl start docker

# 현재 사용자가 docker 그룹에 있는지 확인
groups $USER
# docker 그룹이 없으면:
sudo usermod -aG docker $USER
# 로그아웃 후 재로그인 또는:
newgrp docker
```

**macOS:**
```bash
# Docker Desktop이 실행 중인지 확인 (메뉴바 고래 아이콘)
# 실행이 안 되어 있으면 Applications에서 Docker.app 실행
open -a Docker
# 잠시 기다린 후 재시도
```

**Windows:**
```powershell
# Docker Desktop 앱이 실행 중인지 확인 (시스템 트레이 고래 아이콘)
# WSL 2 상태 확인
wsl --status
# WSL 2 업데이트
wsl --update
```

---

#### 오류: `docker compose` 명령어를 찾을 수 없음

**증상:**
```
docker: 'compose' is not a docker command.
# 또는
bash: docker-compose: command not found
```

**원인 및 해결:**

이 프로젝트는 **Docker Compose v2** (`docker compose`, 하이픈 없음)를 사용합니다.

```bash
# 버전 확인
docker compose version
# Docker Compose version v2.x.x   ← 이렇게 나와야 정상

# v1 (docker-compose)이 설치된 경우 v2로 업그레이드:
# Linux
sudo apt-get remove docker-compose
# Docker Desktop 재설치 또는 Compose Plugin 설치:
sudo apt-get install docker-compose-plugin
```

---

#### 오류: `Bind for 0.0.0.0:5432 failed: port is already allocated`

**증상:**
```
Error response from daemon: driver failed programming external connectivity...
Bind for 0.0.0.0:5432 failed: port is already allocated
```

**원인**: 로컬에 PostgreSQL이 이미 설치·실행 중이어서 포트가 충돌합니다.

**해결:**

```bash
# 로컬 PostgreSQL 서비스 중지

# Linux
sudo systemctl stop postgresql

# macOS (Homebrew)
brew services stop postgresql@16
# 또는
pg_ctl -D /usr/local/var/postgresql@16 stop

# Windows
net stop postgresql-x64-16
# 또는 서비스 관리자(services.msc)에서 PostgreSQL 서비스 중지
```

Redis 포트(6379) 충돌 시:
```bash
# Linux/macOS
sudo systemctl stop redis

# macOS Homebrew
brew services stop redis

# Windows
net stop Redis
```

---

#### 오류: `no space left on device`

**증상:**
```
failed to create shim task: ... no space left on device
```

**해결:**
```bash
# 사용하지 않는 Docker 리소스 정리
docker system prune -a --volumes

# 특정 볼륨만 삭제 (데이터 초기화 포함)
docker compose -f infra/docker/docker-compose.yml down -v
```

> ⚠️ `-v` 옵션은 **PostgreSQL, Kafka, Redis 데이터를 모두 삭제**합니다. 주의!

---

### 11.2 PostgreSQL 관련 오류

---

#### 오류: Flyway 마이그레이션 실패 — `Unable to obtain connection`

**증상:**
```
org.flywaydb.core.api.exception.FlywayException:
  Unable to obtain connection from database:
  Connection refused. Check that the hostname and port are correct
  and that the postmaster is accepting TCP/IP connections.
```

**원인**: PostgreSQL 컨테이너가 아직 준비되지 않았거나 포트가 다릅니다.

**해결:**
```bash
# PostgreSQL 컨테이너 상태 확인
docker compose -f infra/docker/docker-compose.yml ps postgres
# STATUS 컬럼이 "Up (healthy)" 인지 확인

# healthy 상태가 아니면 로그 확인
docker compose -f infra/docker/docker-compose.yml logs postgres

# postgres 컨테이너 재시작
docker compose -f infra/docker/docker-compose.yml restart postgres

# 준비 완료까지 대기 후 Spring Boot 재기동
./gradlew :ido:bootRun
```

---

#### 오류: `FATAL: role "onepass" does not exist`

**증상:**
```
FATAL: role "onepass" does not exist
```

**원인**: `init-db.sql`이 실행되지 않아 스키마/유저가 없습니다.

**해결:**
```bash
# PostgreSQL 컨테이너 완전 초기화 (볼륨 삭제)
docker compose -f infra/docker/docker-compose.yml down -v postgres
docker compose -f infra/docker/docker-compose.yml up -d postgres

# 또는 직접 초기화 스크립트 실행
docker exec -i onepass-postgres \
  psql -U onepass -d onepass < infra/docker/init-db.sql
```

---

#### 오류: `ERROR: schema "qsign" already exists`

**증상**: Flyway 마이그레이션에서 스키마 중복 오류 발생

**해결:**
```bash
# Flyway baseline 재설정 (마이그레이션 기록 테이블 초기화)
docker exec -it onepass-postgres psql -U onepass -d onepass -c \
  "DELETE FROM ido.flyway_schema_history WHERE version = '1';"

# 또는 완전 초기화 (DB 볼륨 삭제)
docker compose -f infra/docker/docker-compose.yml down -v
docker compose -f infra/docker/docker-compose.yml up -d
```

---

### 11.3 Kafka / Zookeeper 관련 오류

---

#### 오류: `kafka-init` 컨테이너가 exit code 1로 종료

**증상:**
```
docker compose ps 에서 kafka-init: Exited (1)
```

**해결:**
```bash
# kafka-init 로그 확인
docker compose -f infra/docker/docker-compose.yml logs kafka-init

# Kafka가 완전히 기동된 후 kafka-init 재실행
docker compose -f infra/docker/docker-compose.yml restart kafka-init

# 또는 수동으로 토픽 생성 스크립트 실행
docker exec -it onepass-kafka bash /create-topics.sh
# 위가 안 되면:
docker exec -it onepass-kafka \
  kafka-topics --bootstrap-server localhost:9092 \
  --create --topic qsign.auth.events \
  --partitions 6 --replication-factor 1
```

---

#### 오류: `org.apache.kafka.common.errors.TimeoutException`

**증상:**
```
TimeoutException: Topic qsign.auth.events not present in metadata after 60000 ms
# 또는
org.springframework.kafka.KafkaException: Could not start bean 'xxxKafkaListenerContainerFactory'
```

**원인**: Spring Boot가 Kafka에 연결을 시도할 때 Kafka가 아직 준비되지 않았습니다.

**해결:**
```bash
# Kafka 상태 확인
docker compose -f infra/docker/docker-compose.yml ps kafka
# "Up (healthy)" 인지 확인

# Kafka 기동까지 30~60초 대기 후 Spring Boot 재기동
./gradlew :ido:bootRun

# Kafka 브로커 직접 연결 테스트
docker exec -it onepass-kafka \
  kafka-broker-api-versions --bootstrap-server localhost:9092
```

---

#### 오류: `LEADER_NOT_AVAILABLE` (Kafka 토픽 생성 직후)

**증상:**
```
WARN [AdminClient clientId=...] Received error ...LEADER_NOT_AVAILABLE for topic...
```

**원인**: 토픽이 방금 생성되어 리더 선출이 완료되지 않은 일시적 상태입니다.

**해결**: 10~15초 대기 후 자동 해소됩니다. 애플리케이션 재시도 로직이 있으므로 대부분 자동 복구됩니다.

---

#### 오류: Zookeeper `Connection refused`

```bash
# Zookeeper 상태 확인
docker compose -f infra/docker/docker-compose.yml logs zookeeper | tail -20

# Zookeeper 재시작 (Kafka도 함께 재시작 필요)
docker compose -f infra/docker/docker-compose.yml restart zookeeper
# Zookeeper healthy 확인 후
docker compose -f infra/docker/docker-compose.yml restart kafka
```

---

### 11.4 Redis 관련 오류

---

#### 오류: `Unable to connect to Redis`

**증상:**
```
org.springframework.data.redis.RedisConnectionFailureException:
  Unable to connect to Redis
```

**해결:**
```bash
# Redis 상태 확인
docker compose -f infra/docker/docker-compose.yml ps redis

# Redis 연결 테스트
docker exec -it onepass-redis redis-cli ping
# PONG 이 나와야 정상

# Redis 재시작
docker compose -f infra/docker/docker-compose.yml restart redis

# Redis 설정 파일 오류 확인
docker compose -f infra/docker/docker-compose.yml logs redis
```

---

#### 오류: `OOM command not allowed when used memory > maxmemory`

**증상:**
```
io.lettuce.core.RedisCommandExecutionException: OOM command not allowed when used memory > 'maxmemory'
```

**원인**: Redis 메모리 한도 초과

**해결:**
```bash
# Redis 메모리 사용량 확인
docker exec -it onepass-redis redis-cli info memory | grep used_memory_human

# 전체 캐시 삭제 (주의: 모든 세션 및 캐시 데이터 삭제)
docker exec -it onepass-redis redis-cli FLUSHALL

# 또는 redis.conf의 maxmemory 값 증가 후 재시작
# infra/docker/redis/redis.conf 에서 maxmemory 512mb → 1024mb 변경
docker compose -f infra/docker/docker-compose.yml restart redis
```

---

### 11.5 Gradle / Java 빌드 오류

---

#### 오류: `Could not find or load main class`

**증상:**
```
Error: Could not find or load main class kr.go.smes.ido.IdoApplication
```

**해결:**
```bash
# 클린 빌드
./gradlew clean
./gradlew :ido:build -x test

# Java 버전 확인 (반드시 21이어야 함)
java -version
# openjdk version "21.x.x" 이어야 함

# JAVA_HOME 확인
echo $JAVA_HOME
```

---

#### 오류: `Unsupported class file major version 65`

**증상:**
```
java.lang.UnsupportedClassVersionError: ... has been compiled by a more recent version
of the Java Runtime (class file version 65.0), this version of the Java Runtime only
recognizes class file versions up to 61.0
```

**원인**: JDK 17(class version 61)로 실행하려는데 코드가 JDK 21(class version 65)로 빌드됨.

**해결:**
```bash
# JDK 21 설치 확인
java -version
# "21.x.x" 이어야 함. 17이면 21로 교체 필요

# macOS SDKMAN 사용자
sdk use java 21.0.7-tem

# Windows: JAVA_HOME 환경변수가 JDK 21 경로인지 확인
echo $env:JAVA_HOME
```

---

#### 오류: `Gradle build failed — compilation error`

**증상:**
```
> Task :ido:compileJava FAILED
error: cannot find symbol
```

**해결:**
```bash
# 1. platform-common을 먼저 빌드 (다른 모듈의 의존성)
./gradlew :platform-common:build

# 2. 클린 후 전체 재빌드
./gradlew clean
./gradlew :platform-common:build :q-sign:build :q-im:build :ido:build :agency-stub:build -x test

# 3. 자세한 오류 출력
./gradlew :ido:build -x test --info 2>&1 | grep -A 5 "error:"
```

---

#### 오류: `Could not resolve` (Gradle 의존성 다운로드 실패)

**증상:**
```
Could not resolve org.springframework.boot:spring-boot-starter-web:3.5.9
```

**원인**: 네트워크 문제 또는 Maven Central 접근 불가

**해결:**
```bash
# Gradle 캐시 삭제 후 재시도
# Linux/macOS
rm -rf ~/.gradle/caches/

# Windows PowerShell
Remove-Item -Recurse -Force "$env:USERPROFILE\.gradle\caches"

# 재시도
./gradlew :ido:build -x test

# 프록시 환경인 경우 gradle.properties에 프록시 설정 추가
# ~/.gradle/gradle.properties 파일에:
# systemProp.http.proxyHost=proxy.example.com
# systemProp.http.proxyPort=8080
# systemProp.https.proxyHost=proxy.example.com
# systemProp.https.proxyPort=8080
```

---

#### 오류: Gradle Wrapper 다운로드 실패

**증상:**
```
Exception in thread "main" java.net.ConnectException: Connection timed out
Downloading https://services.gradle.org/distributions/gradle-9.5.0-bin.zip
```

**해결:**
```bash
# Gradle Wrapper를 수동 다운로드 후 설치
# 1. https://services.gradle.org/distributions/gradle-9.5.0-bin.zip 수동 다운로드
# 2. 아래 경로에 배치:

# Linux/macOS
mkdir -p ~/.gradle/wrapper/dists/gradle-9.5.0-bin/[해시값]/
cp gradle-9.5.0-bin.zip ~/.gradle/wrapper/dists/gradle-9.5.0-bin/[해시값]/

# 또는 Gradle 직접 설치 (SDKMAN 사용 시)
sdk install gradle 9.5
```

---

### 11.6 Spring Boot 기동 오류

---

#### 오류: `Port 8081 is already in use`

**증상:**
```
Web server failed to start. Port 8081 was already in use.
```

**해결:**

```bash
# 포트 사용 중인 프로세스 확인 및 종료

# Linux/macOS
lsof -i :8081
kill -9 [PID]

# 또는 한 번에
kill -9 $(lsof -ti :8081)

# Windows PowerShell
netstat -ano | findstr :8081
taskkill /PID [PID] /F

# Windows — 모든 포트 한꺼번에 확인
netstat -ano | findstr "8081\|8082\|8083\|8084"
```

**각 서비스 포트:**

| 서비스 | 포트 |
|--------|------|
| q-sign | 8081 |
| q-im | 8082 |
| ido | 8083 |
| agency-stub | 8084 |

---

#### 오류: `ApplicationContext` 로드 실패 — Kafka 연결 오류

**증상:**
```
org.apache.kafka.common.errors.TimeoutException: Timed out waiting for a node assignment
```

**해결 순서:**
```bash
# 1. Kafka 컨테이너 상태 확인
docker compose -f infra/docker/docker-compose.yml ps kafka
# "Up (healthy)" 이어야 함

# 2. healthy 상태 아니면 30초 대기
sleep 30

# 3. Kafka 직접 연결 테스트
docker exec -it onepass-kafka \
  kafka-broker-api-versions --bootstrap-server localhost:9092

# 4. 모두 정상이면 Spring Boot 재기동
./gradlew :ido:bootRun
```

---

#### 오류: `Caused by: javax.net.ssl.SSLHandshakeException`

**증상**: HTTPS 관련 SSL 오류

**해결:**
```bash
# 로컬 개발 환경에서는 HTTP를 사용하므로 SSL 설정이 필요 없습니다.
# application.yml의 server.servlet.session.cookie.secure: true 설정이
# HTTP 로컬에서 문제를 일으킬 수 있습니다.

# 임시 해결 (로컬 전용):
# application.yml에서 아래 설정을 false로 변경
# server.servlet.session.cookie.secure: false
```

---

### 11.7 Flyway 마이그레이션 오류

---

#### 오류: `FlywayException: Validate failed: Migrations have failed validation`

**증상:**
```
Flyway: Migrations have failed validation.
Migration checksum mismatch for migration version 1
-> Applied to database : 1234567890
-> Resolved locally    : 9876543210
```

**원인**: 이미 적용된 마이그레이션 SQL 파일이 변경되었습니다.

**해결:**
```bash
# 방법 1: Flyway repair (체크섬 재정의)
# application.yml에 아래 추가 (임시)
# spring.flyway.repair-on-migrate: true
# 기동 후 해당 설정 제거

# 방법 2: 마이그레이션 기록 수동 수정 (개발 환경 전용)
docker exec -it onepass-postgres psql -U onepass -d onepass -c \
  "UPDATE ido.flyway_schema_history SET checksum = [새_체크섬] WHERE version = '1';"

# 방법 3: 완전 초기화 (데이터 전부 삭제, 개발 환경만)
docker compose -f infra/docker/docker-compose.yml down -v
docker compose -f infra/docker/docker-compose.yml up -d
```

---

#### 오류: `FlywayException: Found non-empty schema(s) "qsign" without schema history table`

**증상:**
```
Found non-empty schema(s) "qsign" without schema history table!
Use baseline() or set baselineOnMigrate to true to initialize the schema history table.
```

**해결:**
```yaml
# application.yml에 아래 추가 (이미 설정되어 있어야 함)
spring:
  flyway:
    baseline-on-migrate: true
    baseline-version: 0
```

이미 설정되어 있다면 DB를 초기화하고 재시도:
```bash
docker compose -f infra/docker/docker-compose.yml down -v
docker compose -f infra/docker/docker-compose.yml up -d
```

---

### 11.8 프론트엔드 (Node.js / Yarn) 오류

---

#### 오류: `node: /lib/x86_64-linux-gnu/libc.so.6: version GLIBC_2.28 not found`

**원인**: Node.js 20이 요구하는 glibc 버전이 없습니다 (구형 Linux).

**해결:**
```bash
# Ubuntu 18.04 이하의 경우 nvm으로 Node.js 18 LTS 사용
nvm install 18
nvm use 18
```

---

#### 오류: `yarn: command not found`

**해결:**
```bash
# npm으로 yarn 설치
npm install -g yarn

# macOS Homebrew
brew install yarn

# 버전 확인
yarn --version
```

---

#### 오류: `ERROR: ENOSPC: System limit for number of file watchers reached`

**증상**: Linux에서 webpack-dev-server 기동 시 발생

**해결:**
```bash
# 파일 감시자 한도 증가
echo fs.inotify.max_user_watches=524288 | sudo tee -a /etc/sysctl.conf
sudo sysctl -p

# 또는 임시 적용
sudo sysctl fs.inotify.max_user_watches=524288
```

---

#### 오류: `EACCES: permission denied, mkdir '/usr/local/lib/node_modules'`

**원인**: Node.js 전역 패키지 설치 권한 없음

**해결:**
```bash
# npm의 전역 설치 디렉토리를 홈 디렉토리로 변경
mkdir -p ~/.npm-global
npm config set prefix '~/.npm-global'
echo 'export PATH=~/.npm-global/bin:$PATH' >> ~/.bashrc
source ~/.bashrc
npm install -g yarn
```

---

#### 오류: `Module not found: Can't resolve '@/components/...'`

**원인**: TypeScript path alias 설정 문제

**해결:**
```bash
# node_modules 삭제 후 재설치
cd onepass-fe/frontend
rm -rf node_modules
yarn install

# webpack.config.js의 resolve.alias 설정 확인
# tsconfig.json의 paths 설정 확인
```

---

#### 오류: `Proxy error: Could not proxy request /api/...`

**증상**: 프론트엔드에서 API 호출 시 `ECONNREFUSED`

**원인**: `ido` 서버(port 8083)가 실행되지 않음

**해결:**
```bash
# ido 서버 상태 확인
curl http://localhost:8083/actuator/health

# ido 서버 기동 (별도 터미널)
./gradlew :ido:bootRun
```

---

### 11.9 Keycloak 관련 오류

---

#### 오류: `Connection refused` — Keycloak에 연결할 수 없음

**증상:**
```
java.net.ConnectException: Connection refused: localhost/127.0.0.1:8081
# 또는 q-sign 로그에서:
ERROR KeycloakCallbackService - Keycloak token endpoint 호출 실패
```

**원인**: Keycloak 컨테이너가 기동되지 않았습니다.

**해결:**
```bash
# Keycloak 컨테이너 상태 확인
docker compose -f infra/docker/docker-compose.yml ps | grep keycloak

# Keycloak 기동 (--profile keycloak 필요)
docker compose -f infra/docker/docker-compose.yml --profile keycloak up -d

# 기동까지 대기 (1~2분 소요)
docker compose -f infra/docker/docker-compose.yml logs -f keycloak

# healthy 확인
curl -s http://localhost:8081/health/ready
# {"status":"UP"} 이면 정상
```

---

#### 오류: `Realm 'onepass' not found` — realm-export.json 임포트 실패

**증상:**
```
# Keycloak 로그에서:
ERROR: Failed to import realm: File not found
# 또는 curl http://localhost:8081/realms/onepass 에서 404
```

**원인**: `realm-export.json` 마운트 경로 오류 또는 파일 누락

**해결:**
```bash
# realm-export.json 파일 존재 확인
ls -la infra/docker/keycloak/realm-export.json

# 파일이 없으면 git에서 복구
git checkout -- infra/docker/keycloak/realm-export.json

# Keycloak 컨테이너 재시작 (볼륨은 유지)
docker compose -f infra/docker/docker-compose.yml restart keycloak

# 재시작 후 Realm 임포트 확인
curl -s http://localhost:8081/realms/onepass | python3 -m json.tool | grep '"realm"'
# "realm": "onepass" 이면 정상

# 임포트가 여전히 실패하면 컨테이너 재생성
docker compose -f infra/docker/docker-compose.yml --profile keycloak down
docker compose -f infra/docker/docker-compose.yml --profile keycloak up -d
```

---

#### 오류: `authorizationUrl`이 `kauth.kakao.com`으로 시작함 (잘못된 URL)

**증상:**
```json
{ "authorizationUrl": "https://kauth.kakao.com/oauth/authorize?..." }
```

**원인**: q-sign의 `application.yml`이 올바르게 적용되지 않았거나 이전 버전의 JAR가 실행 중입니다.

**해결:**
```bash
# 1. 클린 빌드 후 재기동
./gradlew :q-sign:clean :q-sign:build -x test
./gradlew :q-sign:bootRun

# 2. q-sign application.yml에 Keycloak 설정 확인
grep -A 10 "keycloak:" q-sign/src/main/resources/application.yml
# qsign.keycloak.base-url 항목이 있어야 함

# 3. 환경변수 확인 (로컬 기동 시)
echo $QSIGN_KEYCLOAK_BASE_URL
# 값이 없으면 application.yml 기본값(http://localhost:8081) 사용
```

---

#### 오류: `state 검증 실패` — CSRF state 불일치

**증상:**
```
PlatformException: IDP_SIGNATURE_MISMATCH — state 검증 실패 (만료 또는 불일치)
# 브라우저 리다이렉트: /error?code=E-IDP-003
```

**원인 및 해결:**
1. **state TTL 만료** (기본 300초): 인증 페이지를 5분 이상 방치 후 로그인 시도
   ```bash
   # state TTL 확인 (application.yml)
   grep "state-ttl-seconds" q-sign/src/main/resources/application.yml
   # 기본값: 300 (5분). 개발 시 600으로 늘릴 수 있음
   ```
2. **Redis 연결 끊김**: state가 저장되지 않은 경우
   ```bash
   docker exec -it onepass-redis redis-cli ping
   # PONG 이면 정상
   docker compose -f infra/docker/docker-compose.yml restart redis
   ```
3. **중복 콜백 요청**: 브라우저 새로고침 등으로 콜백이 두 번 실행된 경우
   - state는 1회 소비(consume) 후 삭제되므로 재사용 불가
   - 재인증 안내 후 처음부터 다시 시도

---

#### 오류: `IDP_SIGNATURE_MISMATCH` — Keycloak JWKS 서명 검증 실패

**증상:**
```
PlatformException: IDP_SIGNATURE_MISMATCH — JWKS 서명 검증 실패
```

**원인**: Keycloak JWKS 엔드포인트에서 공개키를 가져오지 못하거나, 키가 로테이션된 경우

**해결:**
```bash
# 1. Keycloak JWKS 엔드포인트 접근 가능 확인
curl -s http://localhost:8081/realms/onepass/protocol/openid-connect/certs \
  | python3 -m json.tool | grep '"kid"'
# kid 값이 있으면 정상

# 2. keycloakJwks 캐시 만료 강제 (Spring Boot 재시작으로 캐시 초기화)
# q-sign 재시작 시 캐시가 초기화됨
./gradlew :q-sign:bootRun

# 3. Keycloak 로그에서 오류 확인
docker compose -f infra/docker/docker-compose.yml logs keycloak | tail -50
```

---

#### 오류: `QSIGN_KEYCLOAK_CLIENT_SECRET` 미설정으로 token exchange 실패

**증상:**
```
# Keycloak 응답:
{"error":"unauthorized_client","error_description":"Invalid client credentials"}
```

**원인**: q-sign의 Client Secret이 잘못 설정되었거나 미설정

**해결:**
```bash
# 1. Keycloak Admin Console에서 q-sign-client Secret 확인
# http://localhost:8081 → admin/admin
# Clients → q-sign-client → Credentials 탭 → Secret 복사

# 2. 환경변수로 설정 (Linux/macOS)
export QSIGN_KEYCLOAK_CLIENT_SECRET="복사한-시크릿-값"
./gradlew :q-sign:bootRun

# Windows PowerShell
$env:QSIGN_KEYCLOAK_CLIENT_SECRET="복사한-시크릿-값"
.\gradlew.bat :q-sign:bootRun

# 3. application.yml에 직접 설정 (로컬 개발 전용 — 운영 사용 금지)
# qsign.keycloak.client-secret: "직접-값-입력"
```

---

#### 오류: `kc_idp_hint`가 URL에 없음 — IdP 힌트 매핑 오류

**증상**: authorizationUrl에 `kc_idp_hint` 파라미터가 없음

**원인**: `qsign.keycloak.idp-hint-mapping`에 해당 provider가 없음

**해결:**
```bash
# application.yml 매핑 확인
grep -A 10 "idp-hint-mapping" q-sign/src/main/resources/application.yml
# 출력 예시:
#   idp-hint-mapping:
#     kakao: social-kakao
#     naver: social-naver
#     pass: social-pass
#     gpki: social-gpki

# 매핑이 없으면 추가 후 재기동
./gradlew :q-sign:bootRun
```

---

#### 오류: Keycloak Admin Console 로그인 불가 (포트 8081 충돌)

**증상**: `http://localhost:8081` 접속 시 q-sign API 응답이 옴 (Keycloak 화면 아님)

**원인**: q-sign(8081)과 Keycloak(8081) 포트 충돌

**중요**: 이 프로젝트에서 **Keycloak은 8081 포트**를 사용합니다.
q-sign이 `bootRun`으로 실행 중이면 Keycloak 컨테이너와 포트가 충돌합니다.

**해결:**
```bash
# q-sign은 JAR로 별도 포트(8081)에서 실행되지만
# 로컬 개발 시 q-sign bootRun과 Keycloak 컨테이너를 동시에
# 같은 포트에서 실행할 수 없음.
# → Keycloak은 컨테이너, q-sign은 다른 포트로 기동하거나
# → docker-compose.yml에서 Keycloak 포트를 8088로 변경 후 application.yml도 수정

# 임시 해결: Keycloak 포트를 8088로 변경
# infra/docker/docker-compose.yml 내 keycloak 서비스:
#   ports: "8088:8080"  (8081→8088 변경)
# q-sign/src/main/resources/application.yml:
#   qsign.keycloak.base-url: http://localhost:8088
```

> 💡 **권장**: 로컬에서 전체 스택 통합 테스트 시 백엔드 서비스를 `bootRun`이 아닌
> Docker 컨테이너로 실행하면 포트 충돌을 피할 수 있습니다.

---

### 11.10 Windows 전용 오류

---

#### 오류: `'\r': command not found` (Git Bash에서 gradlew 실행 시)

**원인**: Windows CRLF 줄바꿈이 gradlew 스크립트에 적용됨

**해결:**
```bash
# Git Bash에서 실행
sed -i 's/\r//' gradlew
chmod +x gradlew

# 또는 Git 설정으로 체크아웃 시 줄바꿈 변환 방지
git config core.autocrlf false
git checkout -- gradlew
```

---

#### 오류: `The filename or extension is too long` (Windows 경로 길이 제한)

**원인**: Windows 기본 최대 경로 길이(260자)가 node_modules의 중첩된 경로를 수용하지 못함

**해결:**
```powershell
# 관리자 PowerShell에서 Long Path 활성화
New-ItemProperty -Path "HKLM:\SYSTEM\CurrentControlSet\Control\FileSystem" `
  -Name "LongPathsEnabled" -Value 1 -PropertyType DWORD -Force

# Git 설정
git config --system core.longpaths true

# 재부팅 후 재시도
```

---

#### 오류: Docker Desktop — `WSL 2 installation is incomplete`

**해결:**
```powershell
# 관리자 PowerShell에서
dism.exe /online /enable-feature /featurename:Microsoft-Windows-Subsystem-Linux /all /norestart
dism.exe /online /enable-feature /featurename:VirtualMachinePlatform /all /norestart

# 재부팅 후
wsl --update
wsl --set-default-version 2
```

---

#### 오류: `JAVA_HOME is not set` (Windows)

**해결:**
```powershell
# 관리자 PowerShell에서 시스템 환경변수 등록
[System.Environment]::SetEnvironmentVariable(
  "JAVA_HOME",
  "C:\Program Files\Eclipse Adoptium\jdk-21.0.7.6-hotspot",
  "Machine"
)

# 새 PowerShell 창에서 확인
echo $env:JAVA_HOME
```

---

#### 오류: `gradlew.bat` 실행 시 `'.' is not recognized as an internal or external command`

**해결:**
```cmd
REM CMD에서는 경로에 .\ 불필요
gradlew.bat :ido:bootRun

REM PowerShell에서는 .\ 필요
.\gradlew.bat :ido:bootRun
```

---

### 11.11 macOS 전용 오류

---

#### 오류: `Operation not permitted` (Apple Silicon M1/M2/M3)

**원인**: Rosetta 2 없이 x86 이미지 실행 시도 또는 macOS 보안 제한

**해결:**
```bash
# Rosetta 2 설치
softwareupdate --install-rosetta --agree-to-license

# Docker Desktop → Settings → General
# "Use Rosetta for x86/amd64 emulation on Apple Silicon" 체크

# ARM 네이티브 이미지 사용 (일부 이미지는 자동 선택)
docker pull --platform linux/arm64 postgres:16-alpine
```

---

#### 오류: `docker: Error response from daemon: Ports are not available: listen tcp 0.0.0.0:5432: bind: address already in use`

**원인**: macOS에서 기본 PostgreSQL 또는 다른 서비스가 포트를 점유 중

**해결:**
```bash
# 포트 사용 프로세스 확인
lsof -i :5432
lsof -i :6379

# 서비스 중지
brew services stop postgresql@16
brew services stop redis

# 또는 프로세스 강제 종료
kill -9 $(lsof -ti :5432)
```

---

#### 오류: `zsh: permission denied: ./gradlew`

**해결:**
```bash
chmod +x gradlew
./gradlew --version
```

---

#### 오류: Docker Desktop 메모리 부족으로 컨테이너 OOM

**증상**: 컨테이너가 `OOMKilled` 상태로 계속 재시작

**해결:**
```
Docker Desktop → Settings → Resources
- Memory: 8GB 이상으로 증가 (최소 6GB)
- Apply & Restart
```

---

## 12. 개발 Tips 및 유용한 명령어

### 12.1 Gradle 빠른 명령어 모음

```bash
# 특정 모듈만 빌드 (빠름)
./gradlew :ido:build -x test

# 변경된 모듈만 빌드 (Gradle incremental build)
./gradlew :ido:bootRun   # 변경 감지 자동

# 전체 클린 빌드
./gradlew clean build -x test

# 의존성 트리 확인
./gradlew :ido:dependencies

# 사용 가능한 태스크 목록
./gradlew :ido:tasks

# Gradle 데몬 중지 (메모리 해제)
./gradlew --stop
```

### 12.2 Docker 유용한 명령어

```bash
# 실행 중인 컨테이너 전체 상태 (헬스체크 포함)
docker compose -f infra/docker/docker-compose.yml ps

# 특정 서비스 재시작
docker compose -f infra/docker/docker-compose.yml restart kafka

# 컨테이너 내부 접속
docker exec -it onepass-postgres bash
docker exec -it onepass-kafka bash
docker exec -it onepass-redis redis-cli

# 로그 실시간 확인 (최근 100줄부터)
docker compose -f infra/docker/docker-compose.yml logs -f --tail=100 kafka

# 컨테이너 리소스 사용량 확인
docker stats

# 네트워크 확인
docker network ls
docker network inspect onepass_onepass-net
```

### 12.3 PostgreSQL 유용한 명령어

```bash
# psql 접속
docker exec -it onepass-postgres psql -U onepass -d onepass

# 스키마별 테이블 목록
\dn          -- 스키마 목록
\dt qsign.*  -- qsign 스키마 테이블 목록
\dt ido.*    -- ido 스키마 테이블 목록

# 특정 테이블 조회
SELECT * FROM ido.agency_meta LIMIT 10;
SELECT * FROM qim.qim_user LIMIT 10;

# Flyway 마이그레이션 이력 확인
SELECT * FROM ido.flyway_schema_history ORDER BY installed_on;
```

### 12.4 Redis 유용한 명령어

```bash
# Redis CLI 접속
docker exec -it onepass-redis redis-cli

# FE 세션 키 목록
KEYS fe:session:*

# 특정 세션 조회
GET fe:session:[feSessionId]

# 사용자별 세션 목록
SMEMBERS fe:user-sessions:[qimUserId]

# OIDC 상태 키 확인
KEYS oidc:state:*

# 전체 키 개수
DBSIZE

# 메모리 사용량
INFO memory
```

### 12.5 Kafka 유용한 명령어

```bash
# 토픽 목록 확인
docker exec -it onepass-kafka \
  kafka-topics --bootstrap-server localhost:9092 --list

# 토픽 상세 정보
docker exec -it onepass-kafka \
  kafka-topics --bootstrap-server localhost:9092 \
  --describe --topic qsign.auth.events

# 토픽 메시지 실시간 소비 (처음부터)
docker exec -it onepass-kafka \
  kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic qsign.auth.events \
  --from-beginning

# 컨슈머 그룹 목록
docker exec -it onepass-kafka \
  kafka-consumer-groups --bootstrap-server localhost:9092 --list

# 컨슈머 그룹 lag 확인
docker exec -it onepass-kafka \
  kafka-consumer-groups --bootstrap-server localhost:9092 \
  --describe --group ido-qsign-consumer
```

### 12.6 Spring Boot Actuator 엔드포인트

```bash
# 헬스체크 (상세)
curl http://localhost:8083/actuator/health | python3 -m json.tool

# 환경변수 확인
curl http://localhost:8083/actuator/env | python3 -m json.tool

# 빈 목록
curl http://localhost:8083/actuator/beans | python3 -m json.tool

# 메트릭
curl http://localhost:8083/actuator/metrics
curl http://localhost:8083/actuator/metrics/jvm.memory.used
```

---

## 13. 서비스 종료 방법

### 13.1 Spring Boot 서비스 종료

각 서비스가 실행 중인 터미널에서:

```
Ctrl + C
```

Spring Boot는 Graceful Shutdown이 설정되어 있으므로 진행 중인 요청 처리 후 종료됩니다 (최대 20초).

### 13.2 프론트엔드 개발 서버 종료

React 개발 서버가 실행 중인 터미널에서:

```
Ctrl + C
```

### 13.3 Docker 인프라 종료

```bash
# 컨테이너 중지 (데이터 유지)
docker compose -f infra/docker/docker-compose.yml stop

# 컨테이너 중지 및 제거 (데이터 볼륨 유지)
docker compose -f infra/docker/docker-compose.yml down

# 컨테이너 + 볼륨 전체 제거 (데이터 완전 삭제 — 주의!)
docker compose -f infra/docker/docker-compose.yml down -v
```

> ⚠️ `down -v` 옵션을 사용하면 **PostgreSQL, Kafka, Redis의 모든 데이터가 삭제**됩니다.  
> 다음 기동 시 Flyway 마이그레이션이 처음부터 다시 실행됩니다.

### 13.4 전체 환경 재시작 (클린 재시작)

```bash
# 1. 모든 Spring Boot 서비스 종료 (각 터미널에서 Ctrl+C)

# 2. Docker 인프라 재시작 (데이터 유지)
docker compose -f infra/docker/docker-compose.yml down
docker compose -f infra/docker/docker-compose.yml up -d

# 3. healthy 상태 확인 후 Spring Boot 재기동
docker compose -f infra/docker/docker-compose.yml ps

# 4. Spring Boot 서비스 순서대로 재기동
./gradlew :q-sign:bootRun   # 터미널 1
./gradlew :q-im:bootRun     # 터미널 2
./gradlew :ido:bootRun      # 터미널 3
./gradlew :agency-stub:bootRun  # 터미널 4

# 5. 프론트엔드 재기동
cd onepass-fe/frontend && yarn dev  # 터미널 5
```

---

## 부록 A. 로컬 환경 기동 순서 요약 체크리스트

```
사전 준비
□ JDK 21 설치 확인 (java -version)
□ Docker 및 Docker Compose v2 설치 확인
□ Node.js 20 LTS 설치 확인
□ Yarn 1.22 설치 확인
□ 프로젝트 클론 및 브랜치 확인
□ chmod +x gradlew (Linux/macOS)

Step 1 — 인프라 기동
□ docker compose -f infra/docker/docker-compose.yml up -d
□ 30~60초 대기

Step 2 — 인프라 확인
□ docker compose ps → 모두 "Up (healthy)" 확인
□ kafka-init → "Exited (0)" 확인
□ Kafka 토픽 11개 생성 확인

Step 3 — 백엔드 빌드
□ ./gradlew :platform-common:build :q-sign:build :q-im:build :ido:build :agency-stub:build -x test

Step 4 — 백엔드 기동 (4개 터미널)
□ 터미널 1: ./gradlew :q-sign:bootRun → http://localhost:8081/actuator/health
□ 터미널 2: ./gradlew :q-im:bootRun   → http://localhost:8082/actuator/health
□ 터미널 3: ./gradlew :ido:bootRun    → http://localhost:8083/actuator/health
□ 터미널 4: ./gradlew :agency-stub:bootRun → http://localhost:8084/actuator/health

Step 5 — 프론트엔드 기동 (1개 터미널)
□ 터미널 5: cd onepass-fe/frontend && yarn install && yarn dev
□ http://localhost:3000 접속 확인

모니터링 확인
□ Kafka UI: http://localhost:8090 (admin/admin)
□ Redis Insight: http://localhost:5540
```

---

## 부록 B. 환경별 기본 설정값 요약

| 설정 | 로컬 기본값 | Docker 기본값 |
|------|-----------|-------------|
| PostgreSQL Host | `localhost` | `postgres` |
| PostgreSQL Port | `5432` | `5432` |
| PostgreSQL DB | `onepass` | `onepass` |
| PostgreSQL User | `onepass` | `onepass` |
| PostgreSQL Password | `onepass` | `onepass` |
| Redis Host | `localhost` | `redis` |
| Redis Port | `6379` | `6379` |
| Kafka Servers | `localhost:9092` | `kafka:29092` |
| Q-IM Base URL | `http://localhost:8082` | `http://onepass-qim:8082` |
| Q-Sign Base URL | `http://localhost:8081` | `http://onepass-qsign:8081` |
| IDO Broker Mode | `qsign` | `qsign` |
| Keycloak Base URL | `http://localhost:8088` | `http://keycloak:8080` |
