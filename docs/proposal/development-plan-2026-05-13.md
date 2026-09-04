# OnePass 통합인증 플랫폼 — 개발팀 인계용 실행 플랜 (보수적 분석)

> **작성일**: 2026-05-13  
> **분석 기준**: 소스코드 전수 분석 (Java 271개 파일, FE 600+ 파일)  
> **목적**: 개발팀 인계 시점부터 타이핑만으로 실행 가능한 수준의 코더 플랜  
> **원칙**: 보수적 산정 — 예상의 1.5배 시간으로 계획 수립

---

## 0. 전체 현실적 완성률 평가

| 모듈 | 보수적 완성률 | 운영 가능 여부 | 핵심 차단 요인 |
|------|:---:|:---:|------|
| **ido (BE)** | **68%** | ❌ | `/api/ext/**` forward proxy 전무, CI 처리 경로 미완 |
| **q-sign** | **40%** | ❌ | OIDC 표준 엔드포인트 5개 전무, `issueFromOidc()` 운영 불가 |
| **q-im** | **75%** | ⚠️ 조건부 | 기존 구현 있으나 FE Direct Call 의존 구조 |
| **onepass-fe** | **30%** | ❌ | extInstance 16파일 직접 호출, SKIP_AUTH, AES_GCM_KEY 번들 노출 |
| **infra** | **25%** | ❌ | K8s 매니페스트 전무, CI/CD 전무, Secret 관리 전무 |
| **platform-common** | **85%** | ✅ | 도메인 모델·유틸 완성, SecurePasswordGenerator 완성 |

> **종합 운영 준비도**: **약 47%** — 지금 당장 운영 배포 시 보안 사고 및 기능 장애 확실

---

## 1. 즉시 차단(Blocker) 항목 우선 해결 목록

아래 항목들은 하나라도 미해결 시 **운영 배포 절대 불가** 판정:

| # | 항목 | 위치 | 위험도 |
|---|------|------|:---:|
| B-1 | AES_GCM_KEY JS 번들 노출 | `webpack.config.js:64`, `aesGcm.ts:10` | 🔴 치명 |
| B-2 | SKIP_AUTH=true 전체 인증 우회 | `Private.tsx:67/109/130` | 🔴 치명 |
| B-3 | CI(연계정보) FE 직접 수신 (Q3=B 위반) | `ciToken.ts:18`, `extInstance.ts` | 🔴 치명 |
| B-4 | Math.random() 기업 임시 비밀번호 (비CSPRNG) | `Step5.tsx:73-76` | 🔴 치명 |
| B-5 | extInstance → Q-IM 16파일 직접 호출 (X-API-Key 번들 노출) | `extInstance.ts:6` | 🔴 치명 |
| B-6 | Keycloak `change-me` secret 잔존 | `docker-compose.yml` | 🔴 치명 |
| B-7 | q-sign `issueFromOidc()` 운영 불가 (sub 없는 해시) | `AuthServiceImpl.java:67` | 🔴 치명 |
| B-8 | ido `/api/ext/**` forward proxy 전무 | ido 전체 | 🔴 치명 |

---

## 2. Phase별 실행 계획

### Phase 0: 환경 세팅 (Day 1 — 전체 팀 공통)

#### 0-1. 레포 클론 및 빌드 검증

```bash
# 클론
git clone <repo-url>
cd integration-sso

# 의존성 없는 컴파일 검증 (Docker 불필요)
DOCKER_UNAVAILABLE=true ./gradlew :idem-common:compileJava \
  :idem-registry:compileJava :idem-gate:compileJava :idem-hub:compileJava

# 테스트 (Docker 불필요)
DOCKER_UNAVAILABLE=true ./gradlew :idem-common:test :idem-hub:test --rerun-tasks
# 기대: 212 tests, 0 failures
```

#### 0-2. 로컬 Docker 환경 기동

```bash
cd infra/docker
docker-compose up -d postgres mariadb redis kafka zookeeper schema-registry keycloak
# keycloak 준비 대기 (약 60초)
docker-compose up -d onepass-qsign onepass-qim onepass-ido
```

#### 0-3. .env 파일 생성 (FE 로컬 개발용)

```bash
# idem-console/frontend/.env (로컬 개발 전용 — 절대 커밋 금지)
cat > idem-console/frontend/.env << 'EOF'
APP_ENV=local
SKIP_AUTH=false
NODE_ENV=development
BE_API_ENDPOINT=http://localhost:8083
BE_API_TARGET=http://localhost:8083
# EXT_API_ENDPOINT 및 EXT_API_KEY 는 빈값으로 설정
# → FE에서 /api/ext/** 는 ido forward proxy 완성 후 BE_API_ENDPOINT로 통합
EXT_API_ENDPOINT=
EXT_API_KEY=
AES_GCM_KEY=  # ← 의도적 빈값 — 운영에서는 서버사이드 암호화로 대체
EOF
```

---

### Phase 1: 보안 긴급 패치 (Day 1~3) — FE팀 + BE팀 동시 진행

**담당**: FE팀 (Task 1-1 ~ 1-4), BE팀 (Task 1-5)  
**완료 조건**: 모든 B-1 ~ B-6 Blocker 해소

---

#### Task 1-1: SKIP_AUTH 제거 【FE팀 / 0.5일】

**파일**: `idem-console/frontend/src/AppRoutes/Private.tsx`

**변경 1**: Line 67
```typescript
// BEFORE
if (!isLoggedIn && process.env.SKIP_AUTH !== 'true') {

// AFTER
if (!isLoggedIn) {
```

**변경 2**: Lines 109~117 (if 블록 전체 제거)
```typescript
// BEFORE (lines 109~117)
if (process.env.SKIP_AUTH === 'true') {
    dispatch({
        type: UPDATE_USER_IS_FETCH,
        payload: {
            isUserFetching: false,
        },
    });
    return;
}

// AFTER
// 위 if 블록 전체 삭제 (8줄 제거)
```

**변경 3**: Line 130
```typescript
// BEFORE
const skipAuth = process.env.SKIP_AUTH === 'true';

// AFTER (라인 전체 삭제)
```

**변경 4**: Lines 136~144 (skipAuth 참조 블록)
```typescript
// BEFORE (lines 136~144)
if (skipAuth) {
    dispatch({
        type: UPDATE_USER_IS_FETCH,
        payload: {
            isUserFetching: false,
        },
    });
    return;
}

// AFTER (위 if 블록 전체 삭제)
```

**변경 5**: Lines 178, 182 (skipAuth 조건 제거)
```typescript
// BEFORE (line 178)
if (!skipAuth && isUserFetchingError) {
// AFTER
if (isUserFetchingError) {

// BEFORE (line 182)
if (!skipAuth && isUserFetching) {
// AFTER
if (isUserFetching) {
```

**변경 6**: `webpack.config.js` — SKIP_AUTH DefinePlugin 라인 제거  
**파일**: `idem-console/frontend/webpack.config.js`  
**Line 52**: 아래 라인 삭제
```typescript
// BEFORE (line 52)
SKIP_AUTH: process.env.SKIP_AUTH,
// AFTER: 라인 전체 삭제
```

---

#### Task 1-2: AES_GCM_KEY 번들 노출 제거 【FE팀 / 0.5일】

**배경**: `webpack.config.js`의 `DefinePlugin`이 `AES_GCM_KEY`를 JS 번들에 인라인 삽입.  
번들을 다운받으면 암호화 키 평문 노출. Q3=B 위반의 핵심.

**파일 1**: `idem-console/frontend/webpack.config.js`  
**Line 64**: 아래 라인 삭제
```typescript
// BEFORE (line 64)
AES_GCM_KEY: process.env.AES_GCM_KEY,
// AFTER: 라인 전체 삭제
```

**파일 2**: `idem-console/frontend/src/utils/crypto/aesGcm.ts`  
**전체 파일 삭제**: CI 암호화는 FE가 아닌 ido BE에서 수행해야 함.  
이 파일 자체가 보안 위반 설계의 근거이므로 삭제.

```bash
rm idem-console/frontend/src/utils/crypto/aesGcm.ts
```

> ⚠️ `aesGcm.ts`를 import하는 파일 있으면 해당 import도 함께 제거 필요.  
> 검색: `grep -r "aesGcm\|encryptCi" idem-console/frontend/src --include="*.ts" --include="*.tsx"`

---

#### Task 1-3: Math.random() → 서버사이드 임시 비밀번호 【FE팀 / 0.5일】

**파일**: `idem-console/frontend/src/pages/ConversionSteps/member/Step5.tsx`  
**Lines 72~77** (기업회원 임시 비밀번호 생성 코드):

```typescript
// BEFORE (lines 72~77)
const loginId = data.mbrId;
const password =
    data.password ||
    `Rnd${Math.random().toString(36).slice(2, 10)}!${Math.floor(
        Math.random() * 90 + 10,
    )}`;

// AFTER
const loginId = data.mbrId;
// 임시 비밀번호는 서버에서 CSPRNG로 생성 — 클라이언트 Math.random() 금지
// data.password가 비어있으면 ido BE의 GET /api/v1/auth/provision/temp-password 호출
let password = data.password;
if (!password) {
    const tmpPwResp = await fetch('/api/v1/auth/provision/temp-password', {
        method: 'GET',
        headers: { 'Content-Type': 'application/json' },
    });
    if (!tmpPwResp.ok) {
        setErrorMessage('임시 비밀번호 생성에 실패하였습니다.');
        setFailedModal(true);
        return false;
    }
    const tmpPwData = await tmpPwResp.json();
    password = tmpPwData.tempPassword;
}
```

> **전제 조건**: ido BE의 `GET /api/v1/auth/provision/temp-password` 엔드포인트가  
> `AuthController.java`에 이미 추가되어 있음 (이전 세션에서 구현 완료).  
> `SecurePasswordGenerator.java`가 `platform-common`에 완성 상태.

---

#### Task 1-4: MOCK_MEMBER 초기값 제거 【FE팀 / 0.5일】

**파일**: `idem-console/frontend/src/constants/mockData.ts`

```typescript
// BEFORE (lines 1~9)
export const MOCK_MEMBER = {
    name: '홍길동',           // ← 운영 배포 시 실제 이름이 폼에 자동 입력됨
    phonePrefix: '010',
    phoneSuffix: '12341234', // ← 가짜 번호 자동 입력
    ...
} as const;

// AFTER
export const MOCK_MEMBER = {
    name: '',
    phonePrefix: '',
    phoneSuffix: '',
    telPrefix: '',
    telSuffix: '',
    emailId: '',
    emailDomain: '',
} as const;

export const MOCK_BUSINESS = {
    companyName: '',
    businessNum: '',        // ← '12345677' 제거
    repName: '',
    phonePrefix: '',
    phoneSuffix: '',
    emailId: '',
    emailDomain: '',
} as const;
```

**연동 확인**: `ConversionContext.tsx` lines 76~88에서 MOCK_MEMBER를 참조하므로,  
mockData.ts 수정 후 ConversionContext.tsx는 별도 변경 불필요.

---

#### Task 1-5: docker-compose.yml Secret 교체 【BE팀 / 0.5일】

**파일**: `infra/docker/docker-compose.yml`

```yaml
# BEFORE
KEYCLOAK_CLIENT_SECRET: change-me

# AFTER — 로컬 개발 환경 전용 (운영은 K8s Secret으로 교체)
KEYCLOAK_CLIENT_SECRET: dev-local-secret-$(openssl rand -hex 16)
```

**자동화 스크립트** (`infra/scripts/gen-dev-secrets.sh`):
```bash
#!/bin/bash
# 로컬 개발 환경 전용 secret 생성
KEYCLOAK_SECRET=$(openssl rand -hex 32)
POSTGRES_PW=$(openssl rand -hex 16)
REDIS_PW=$(openssl rand -hex 16)

cat > infra/docker/.env.secrets << EOF
KEYCLOAK_CLIENT_SECRET=${KEYCLOAK_SECRET}
POSTGRES_PASSWORD=${POSTGRES_PW}
REDIS_PASSWORD=${REDIS_PW}
EOF
echo "✅ .env.secrets 생성 완료 — 절대 커밋 금지"
```

---

### Phase 2: extInstance 제거 및 ido Forward Proxy 구현 (Day 3~8) — FE팀 + BE팀

**담당**: BE팀 (Task 2-1: ido forward proxy 구현), FE팀 (Task 2-2: extInstance 교체)  
**의존성**: Task 2-2는 Task 2-1 완료 후 진행 (proxy 없으면 FE API 호출 불가)

---

#### Task 2-1: ido `/api/ext/**` Forward Proxy 구현 【BE팀 / 3일】

**배경**:  
현재 FE의 `extInstance`가 Q-IM(8082)을 직접 호출. 이를 ido(8083)를 통해 중계해야 함.  
`/api/ext/**` → ido가 X-API-Key를 서버 환경변수에서 로드하여 Q-IM으로 포워딩.

**구현할 파일**: `idem-hub/src/main/java/kr/go/smes/idem-hub/ext/ExtProxyController.java` (신규 생성)

```java
package kr.go.smes.ido.ext;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Enumeration;

/**
 * FE → ido → Q-IM Forward Proxy
 *
 * FE의 extInstance가 직접 Q-IM을 호출하는 구조를 ido 중계로 교체.
 * X-API-Key는 서버 환경변수에서 로드 (JS 번들 노출 차단).
 *
 * 매핑 경로:
 *   /api/ext/**  →  {QIM_BASE_URL}/api/ext/**
 *
 * 보안:
 *   - FE 요청에서 X-API-Key 헤더를 수신하지 않음 (제거 후 서버 키 삽입)
 *   - CI 관련 경로(/api/ext/ci/**)는 이 프록시 통과 금지 → 별도 처리
 */
@Slf4j
@RestController
@RequestMapping("/api/ext")
@RequiredArgsConstructor
public class ExtProxyController {

    @Value("${qim.base-url:http://localhost:8082}")
    private String qimBaseUrl;

    @Value("${qim.api-key:}")
    private String qimApiKey;

    private final RestTemplate restTemplate;

    /**
     * CI 경로는 이 프록시에서 차단 — ido AuthService가 전담 처리.
     * /api/ext/ci/** 는 절대 Q-IM 직접 노출 금지 (Q3=B 준수).
     */
    @RequestMapping(value = "/ci/**", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<String> blockCiRoute() {
        log.warn("[ExtProxy] CI 경로 직접 접근 차단 — ido BE 경유 필수");
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body("{\"error\":\"CI 처리는 /api/v1/auth/ 경로를 사용하세요.\"}");
    }

    @RequestMapping(value = "/**",
            method = {RequestMethod.GET, RequestMethod.POST,
                      RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.PATCH})
    public ResponseEntity<byte[]> proxy(
            HttpServletRequest request,
            @RequestBody(required = false) byte[] body) {

        String path = request.getRequestURI(); // /api/ext/...
        String queryString = request.getQueryString();
        String targetUrl = qimBaseUrl + path + (queryString != null ? "?" + queryString : "");

        log.info("[ExtProxy] {} {} → {}", request.getMethod(), path, targetUrl);

        HttpHeaders headers = new HttpHeaders();
        // 원본 헤더 복사 (X-API-Key 제외)
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            if (!"x-api-key".equalsIgnoreCase(name) && !"host".equalsIgnoreCase(name)) {
                headers.set(name, request.getHeader(name));
            }
        }
        // 서버 환경변수의 X-API-Key 삽입
        headers.set("X-API-Key", qimApiKey);
        headers.remove("origin");
        headers.remove("referer");

        HttpEntity<byte[]> entity = new HttpEntity<>(body, headers);
        HttpMethod method = HttpMethod.valueOf(request.getMethod());

        try {
            ResponseEntity<byte[]> response = restTemplate.exchange(
                    URI.create(targetUrl), method, entity, byte[].class);
            return ResponseEntity.status(response.getStatusCode())
                    .headers(response.getHeaders())
                    .body(response.getBody());
        } catch (Exception e) {
            log.error("[ExtProxy] 프록시 요청 실패 url={} cause={}", targetUrl, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(("{\"error\":\"Q-IM 연결 실패\"}").getBytes());
        }
    }
}
```

**RestTemplate 빈 등록** (이미 있으면 스킵):  
파일: `idem-hub/src/main/java/kr/go/smes/idem-hub/config/RestTemplateConfig.java` (신규)

```java
package kr.go.smes.ido.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
```

**application.yml 추가**:  
파일: `idem-hub/src/main/resources/application.yml`  
`spring:` 블록 외부, 최하단에 추가:

```yaml
qim:
  base-url: ${QIM_BASE_URL:http://localhost:8082}
  api-key: ${QIM_API_KEY:}   # 서버 환경변수 — .env에 절대 평문 저장 금지
```

**검증 방법**:
```bash
# ido 재기동 후
curl -X GET http://localhost:8083/api/ext/clients \
  -H "Content-Type: application/json"
# Q-IM의 /api/ext/clients 응답이 ido를 통해 리턴되어야 함
```

---

#### Task 2-2: FE extInstance → beInstance 전환 【FE팀 / 2일】

**전제 조건**: Task 2-1 완료 (ido forward proxy 가동 확인)

**영향 파일 목록** (총 16개 파일, extInstance → beInstance 또는 fetch 교체):

| # | 파일 경로 | Q-IM 직접 호출 경로 | 전환 방법 |
|---|-----------|---------------------|-----------|
| 1 | `src/api/extInstance.ts` | (설정 파일) | 삭제 |
| 2 | `src/api/ext/authResult.ts` | `GET /api/ext/auth-status`, `GET /api/ext/auth-result/{txId}` | beInstance로 교체 |
| 3 | `src/api/ext/businessStatus.ts` | `POST /api/ext/business/status` | beInstance로 교체 |
| 4 | `src/api/ext/businessValidate.ts` | `POST /api/ext/business/validate` | beInstance로 교체 |
| 5 | `src/api/ext/checkConversion.ts` | `POST /api/ext/provision/users/check-conversion` | beInstance로 교체 |
| 6 | `src/api/ext/checkDuplicate.ts` | `GET /api/ext/check-duplicate` | beInstance로 교체 |
| 7 | `src/api/ext/clients.ts` | `GET /api/ext/clients` | beInstance로 교체 |
| 8 | `src/api/ext/consent.ts` | `POST /api/ext/consent/token`, `POST /api/ext/consent` | beInstance로 교체 |
| 9 | `src/api/ext/members.ts` | `GET /api/ext/members/{mbrNo}`, `GET /api/ext/enterprises/{entMbrNo}` 등 | beInstance로 교체 |
| 10 | `src/api/ext/termsBundle.ts` | `GET /api/ext/terms/bundle` | beInstance로 교체 |
| 11 | `src/api/provision/affiliations.ts` | `POST /api/ext/provision/enterprises/{uuid}/affiliations/*` 등 | beInstance로 교체 |
| 12 | `src/api/provision/checkConversion.ts` | `POST /api/ext/provision/users/check-conversion` | beInstance로 교체 |
| 13 | `src/api/provision/ciToken.ts` | `POST /api/ext/ci/token` | **완전 재작성** (↓ 별도 지침) |
| 14 | `src/api/provision/enterprises.ts` | `POST /api/ext/provision/enterprises` | beInstance로 교체 |
| 15 | `src/api/provision/registerEnterprise.ts` | `POST /api/ext/register/enterprise` | beInstance로 교체 |
| 16 | `src/api/provision/registerIndividual.ts` | `POST /api/ext/register/individual` | beInstance로 교체 |
| 17 | `src/api/provision/users.ts` | `POST /api/ext/provision/users` | beInstance로 교체 |

##### 2-2-A. extInstance.ts 삭제

```bash
rm idem-console/frontend/src/api/extInstance.ts
```

##### 2-2-B. beInstance 확인 (이미 존재하는지 확인)

```bash
find idem-console/frontend/src/api -name "beInstance.ts" -o -name "instance.ts" | head -5
# 없으면 신규 생성:
```

`src/api/beInstance.ts` (신규 또는 기존 확인):
```typescript
import axios from 'axios';

/**
 * ido BE를 통한 API 호출 인스턴스.
 * X-API-Key는 ido 서버가 삽입하므로 FE에서 설정 불필요.
 * BE_API_ENDPOINT는 ido (8083) 또는 nginx 리버스 프록시.
 */
const beInstance = axios.create({
    baseURL: process.env.BE_API_ENDPOINT || '',
    headers: {
        'Content-Type': 'application/json',
    },
    withCredentials: true,
});

export default beInstance;
```

##### 2-2-C. 파일별 일괄 교체 (sed 명령)

```bash
cd idem-console/frontend/src

# 모든 api/ext/*.ts, api/provision/*.ts 파일에서 extInstance → beInstance 교체
find api/ext api/provision -name "*.ts" | xargs sed -i \
  "s|import extInstance from 'api/extInstance';|import beInstance from 'api/beInstance';|g"

# extInstance 변수명 → beInstance
find api/ext api/provision -name "*.ts" | xargs sed -i \
  "s|extInstance\.|beInstance.|g"
```

##### 2-2-D. ciToken.ts 완전 재작성 (Q3=B 핵심 수정)

**파일**: `idem-console/frontend/src/api/provision/ciToken.ts`

```typescript
// BEFORE — Q3=B 위반: CI를 FE에서 암호화하여 Q-IM에 직접 전송
import extInstance from 'api/extInstance';
// ...
const response = await extInstance.post('/api/ext/ci/token', params);

// AFTER — CI를 FE가 직접 처리하지 않음. ido BE에 원시 CI 전달 → ido가 암호화 후 Q-IM 호출
```

**`src/api/provision/ciToken.ts` 전체 재작성**:

```typescript
import { ErrorResponseHandler } from 'api/ErrorResponseHandler';
import { AxiosError } from 'axios';
import { ErrorResponse, SuccessResponse } from 'types/api';
import type { CiTokenRequest, CiTokenResponse } from 'types/api/provision/users';

/**
 * CI 토큰 교환 — ido BE 경유 처리 (Q3=B 준수)
 *
 * 변경 전: FE가 CI를 AES-GCM 암호화하여 Q-IM에 직접 POST (보안 위반)
 * 변경 후: FE는 CI 원문을 ido BE의 /api/v1/auth/ci-token 으로 전달.
 *          ido BE가 서버사이드 AES 암호화 후 Q-IM에 포워딩, ciToken(JWT) 반환.
 *
 * ido BE 엔드포인트: POST /api/v1/auth/ci-token
 * Request: { ciPlaintext: string }  (HTTPS TLS 암호화로 전송)
 * Response: { ciToken: string }     (JWT, TTL 5분)
 */
const exchangeCiToken = async (
    params: CiTokenRequest,
): Promise<SuccessResponse<CiTokenResponse> | ErrorResponse> => {
    try {
        const response = await fetch('/api/v1/auth/ci-token', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            credentials: 'include',
            body: JSON.stringify(params),
        });
        if (!response.ok) {
            const err = await response.json();
            return { statusCode: response.status, error: err.message || 'CI 토큰 교환 실패', message: err.message || '', payload: null as any };
        }
        const data = await response.json();
        return { statusCode: 200, error: null, message: 'success', payload: data };
    } catch (error) {
        return ErrorResponseHandler(error as AxiosError);
    }
};

export default exchangeCiToken;
```

> **⚠️ 이 변경과 연동하여 ido BE에 `POST /api/v1/auth/ci-token` 엔드포인트 구현 필요**  
> → Task 2-3 참조

---

#### Task 2-3: ido `POST /api/v1/auth/ci-token` 엔드포인트 구현 【BE팀 / 1일】

**배경**: FE ciToken.ts 재작성(Task 2-2-D)의 서버 측 구현.  
CI 원문을 FE로부터 받아 AES 암호화 후 Q-IM에 전달, ciToken 반환.

**파일**: `idem-hub/src/main/java/kr/go/smes/idem-hub/auth/controller/AuthController.java`  
기존 `AuthController`에 아래 엔드포인트 추가 (기존 파일 수정):

```java
// 기존 import에 추가
import kr.go.smes.ido.auth.dto.CiTokenRequest;
import kr.go.smes.ido.auth.dto.CiTokenResponse;

// 컨트롤러 내부에 추가
/**
 * CI 토큰 교환 — FE에서 CI 원문을 BE에 전달하면 BE가 AES 암호화 후 Q-IM 호출
 * Q3=B 준수: CI는 BE에서만 처리, FE에 반환하지 않음
 *
 * POST /api/v1/auth/ci-token
 */
@PostMapping("/ci-token")
public ResponseEntity<CiTokenResponse> exchangeCiToken(
        @RequestBody @Valid CiTokenRequest request,
        HttpServletRequest httpRequest) {
    log.info("[Auth] CI 토큰 교환 요청");
    CiTokenResponse response = authService.exchangeCiToken(request.getCiPlaintext());
    return ResponseEntity.ok(response);
}
```

**DTO 신규 생성**:

`idem-hub/src/main/java/kr/go/smes/idem-hub/auth/dto/CiTokenRequest.java`:
```java
package kr.go.smes.ido.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
public class CiTokenRequest {
    @NotBlank(message = "ciPlaintext는 필수입니다")
    private String ciPlaintext;
}
```

`idem-hub/src/main/java/kr/go/smes/idem-hub/auth/dto/CiTokenResponse.java`:
```java
package kr.go.smes.ido.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class CiTokenResponse {
    private String ciToken;  // Q-IM 발급 JWT, TTL 5분
}
```

**AuthService에 메서드 추가**:  
파일: `idem-hub/src/main/java/kr/go/smes/idem-hub/auth/service/AuthService.java`

```java
/**
 * CI 원문을 AES-GCM 암호화하여 Q-IM에 전달, ciToken(JWT) 발급.
 * Q3=B 준수: CI 원문은 이 메서드 내에서만 처리, 반환값은 ciToken만.
 *
 * @param ciPlaintext CI 원문 (HTTPS로 수신)
 * @return ciToken Q-IM 발급 JWT
 */
public CiTokenResponse exchangeCiToken(String ciPlaintext) {
    // 1. AES-GCM 암호화 (기존 AesSharedKeyDecryptor 역방향 또는 별도 Encryptor)
    String encryptedCi = qimAesEncryptor.encrypt(ciPlaintext);
    
    // 2. Q-IM POST /api/ext/ci/token 호출 (서버-서버, X-API-Key 서버 환경변수)
    String ciToken = qimClient.exchangeCiToken(encryptedCi);
    
    // 3. ciToken만 FE에 반환 (CI 원문/암호문 미반환)
    return new CiTokenResponse(ciToken);
}
```

> **구현 참조**: `QimSpReceiverController.java`의 `AesSharedKeyDecryptor` 패턴 역방향 활용

---

### Phase 3: q-sign OIDC 표준 엔드포인트 구현 (Day 5~12) — BE팀 (q-sign 담당)

**담당**: BE팀 q-sign 서브팀  
**추정 공수**: 6~8일 (보수적)  
**완료 조건**: Keycloak 연동 OIDC 플로우 End-to-End 동작

---

#### Task 3-1: 현재 q-sign OIDC 구현 상태 정확히 파악 【0.5일】

```bash
# 현재 구현된 파일 확인
find idem-gate/src/main/java -name "*.java" | xargs grep -l \
  "openid\|jwks\|authorize\|well-known\|userinfo" | sort
```

**현재 구현 현황**:
- `KeycloakAuthUrlController.java` — Keycloak으로 리다이렉트하는 URL 생성 ✅
- `KeycloakCallbackService.java` — Keycloak 콜백 처리 ✅
- `KeycloakJwksVerifier.java` — Keycloak JWT 서명 검증 ✅
- `AuthController.java:` `/api/v1/auth/oidc` (POST) ← `issueFromOidc()` 미사용 ❌
- ❌ `/.well-known/openid-configuration` 없음
- ❌ `/authorize` 없음 (Keycloak으로 proxy 또는 직접 구현)
- ❌ `/token` 없음
- ❌ `/userinfo` 없음
- ❌ `/jwks` 없음

**결론**: q-sign은 Keycloak 의존 OIDC 중계자 역할을 해야 하며, 표준 OIDC 엔드포인트 5개 신규 구현 필요.

---

#### Task 3-2: `issueFromOidc()` 운영 가능하도록 수정 【BE팀 / 1일】

**파일**: `idem-gate/src/main/java/kr/go/smes/qsign/application/AuthServiceImpl.java`

**현재 문제** (line ~67):
```java
// BEFORE — sub 없이 임시 해시 생성 → 운영 불가
String identifierHash = computeIdentifierHash(providerCode + ":" + correlationId);
```

**수정 방향**: Keycloak이 발급한 토큰의 `sub` 클레임을 사용해야 함.

```java
// AFTER
// Keycloak sub(사용자 고유 ID)를 기반으로 identifierHash 생성
// input.getSub()는 KeycloakCallbackService에서 JWT 파싱 후 전달
if (input.getSub() == null || input.getSub().isBlank()) {
    throw new PlatformException(PlatformErrorCode.INVALID_INPUT, 
        "OIDC sub 클레임이 없습니다. Keycloak 토큰 검증 실패.");
}
String identifierHash = computeIdentifierHash(
    input.getProviderCode() + ":" + input.getSub());
```

**이슈**: `issueFromOidc()` 입력 DTO에 `sub` 필드 추가 필요.  
`OidcAuthInput.java` 또는 유사 DTO에 `private String sub;` 추가.

---

#### Task 3-3: q-sign OIDC 표준 엔드포인트 5개 구현 【BE팀 / 4일】

**신규 파일**: `idem-gate/src/main/java/kr/go/smes/qsign/api/OidcController.java`

```java
package kr.go.smes.qsign.api;

import kr.go.smes.qsign.keycloak.KeycloakProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/**
 * OIDC 표준 엔드포인트 (RFC 8414, OpenID Connect Core 1.0)
 *
 * q-sign이 Keycloak을 뒤에 두고 OIDC Provider로 동작.
 * 대부분의 엔드포인트는 Keycloak으로 proxy.
 *
 * 엔드포인트:
 *   GET  /.well-known/openid-configuration  — Discovery 문서
 *   GET  /protocol/openid-connect/auth      — Authorization Endpoint
 *   POST /protocol/openid-connect/token     — Token Endpoint
 *   GET  /protocol/openid-connect/userinfo  — UserInfo Endpoint
 *   GET  /protocol/openid-connect/certs     — JWKS Endpoint
 */
@RestController
@RequiredArgsConstructor
public class OidcController {

    private final KeycloakProperties keycloakProperties;
    private final OidcProxyService oidcProxyService;  // Task 3-3-B에서 구현

    // 1. Discovery 문서
    @GetMapping("/.well-known/openid-configuration")
    public ResponseEntity<Map<String, Object>> discoveryDocument() {
        String issuer = keycloakProperties.getIssuer(); // e.g. http://localhost:8080/realms/onepass
        return ResponseEntity.ok(Map.ofEntries(
            Map.entry("issuer", issuer),
            Map.entry("authorization_endpoint", issuer + "/protocol/openid-connect/auth"),
            Map.entry("token_endpoint", issuer + "/protocol/openid-connect/token"),
            Map.entry("userinfo_endpoint", issuer + "/protocol/openid-connect/userinfo"),
            Map.entry("jwks_uri", issuer + "/protocol/openid-connect/certs"),
            Map.entry("response_types_supported", new String[]{"code"}),
            Map.entry("subject_types_supported", new String[]{"public"}),
            Map.entry("id_token_signing_alg_values_supported", new String[]{"RS256"}),
            Map.entry("scopes_supported", new String[]{"openid", "profile", "email"})
        ));
    }

    // 2. Authorization Endpoint — Keycloak으로 리다이렉트
    @GetMapping("/protocol/openid-connect/auth")
    public ResponseEntity<Void> authorize(
            @RequestParam String response_type,
            @RequestParam String client_id,
            @RequestParam String redirect_uri,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String nonce) {
        String keycloakAuthUrl = oidcProxyService.buildKeycloakAuthUrl(
            response_type, client_id, redirect_uri, scope, state, nonce);
        return ResponseEntity.status(302)
                .header("Location", keycloakAuthUrl)
                .build();
    }

    // 3. Token Endpoint — Keycloak으로 proxy
    @PostMapping("/protocol/openid-connect/token")
    public ResponseEntity<String> token(
            @RequestBody(required = false) String body,
            @RequestHeader(required = false) String authorization) {
        return oidcProxyService.proxyToKeycloakToken(body, authorization);
    }

    // 4. UserInfo Endpoint — Keycloak으로 proxy
    @GetMapping("/protocol/openid-connect/userinfo")
    public ResponseEntity<String> userinfo(
            @RequestHeader("Authorization") String authorization) {
        return oidcProxyService.proxyToKeycloakUserinfo(authorization);
    }

    // 5. JWKS Endpoint — Keycloak JWKS 프록시
    @GetMapping("/protocol/openid-connect/certs")
    public ResponseEntity<String> jwks() {
        return oidcProxyService.proxyToKeycloakJwks();
    }
}
```

**신규 파일**: `idem-gate/src/main/java/kr/go/smes/qsign/api/OidcProxyService.java`

```java
package kr.go.smes.qsign.api;

import kr.go.smes.qsign.keycloak.KeycloakProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OidcProxyService {

    private final KeycloakProperties keycloakProperties;
    private final RestTemplate restTemplate;

    public String buildKeycloakAuthUrl(String responseType, String clientId,
            String redirectUri, String scope, String state, String nonce) {
        String base = keycloakProperties.getIssuer() + "/protocol/openid-connect/auth";
        return base + "?response_type=" + responseType
                + "&client_id=" + clientId
                + "&redirect_uri=" + redirectUri
                + (scope != null ? "&scope=" + scope : "")
                + (state != null ? "&state=" + state : "")
                + (nonce != null ? "&nonce=" + nonce : "");
    }

    public ResponseEntity<String> proxyToKeycloakToken(String body, String auth) {
        String url = keycloakProperties.getIssuer() + "/protocol/openid-connect/token";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        if (auth != null) headers.set("Authorization", auth);
        return restTemplate.exchange(url, HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    public ResponseEntity<String> proxyToKeycloakUserinfo(String auth) {
        String url = keycloakProperties.getIssuer() + "/protocol/openid-connect/userinfo";
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", auth);
        return restTemplate.exchange(url, HttpMethod.GET,
                new HttpEntity<>(null, headers), String.class);
    }

    public ResponseEntity<String> proxyToKeycloakJwks() {
        String url = keycloakProperties.getIssuer() + "/protocol/openid-connect/certs";
        return restTemplate.exchange(url, HttpMethod.GET,
                new HttpEntity<>(null, new HttpHeaders()), String.class);
    }
}
```

**RestTemplate 빈**: q-sign 모듈에도 `RestTemplateConfig.java` 동일 방식 추가.

---

#### Task 3-4: InternalSigVerifier strict-mode 운영 설정 확인 【BE팀 / 0.5일】

**파일**: `idem-gate/src/main/resources/application.yml`

현재 기본값 확인:
```bash
grep -n "strict" idem-gate/src/main/resources/application.yml
```

운영 yml에 명시적 설정 추가 (기본값 true이지만 명시):
```yaml
# idem-gate/src/main/resources/application.yml
qsign:
  ido:
    internal-sig-strict-mode: true  # 운영에서 반드시 true — false 시 X-Sig 우회 허용
```

환경별 override 예시 (`application-prod.yml`):
```yaml
qsign:
  ido:
    internal-sig-strict-mode: true
```

---

### Phase 4: Keycloak 설정 및 통합 테스트 (Day 10~14) — DevOps + BE팀

**담당**: DevOps팀 + BE팀  
**전제**: Phase 1~3 완료

---

#### Task 4-1: Keycloak Realm 수동 설정 【DevOps / 1일】

```bash
# Keycloak Admin Console: http://localhost:8080
# 계정: admin / admin (docker-compose 기본값)

# 실행 순서:
# 1. Realm 생성: onepass
# 2. Client 생성: ido-client
#    - Client Protocol: openid-connect
#    - Access Type: confidential
#    - Valid Redirect URIs: http://localhost:8083/api/v1/broker/keycloak/callback
# 3. Client Secret 복사 → ido application.yml 업데이트
# 4. 테스트 사용자 생성 (test@smes.go.kr / Test1234!)
```

**Realm Export로 자동화** (설정 완료 후):
```bash
# Keycloak에서 realm 설정을 JSON으로 export
docker exec -it keycloak /opt/keycloak/bin/kc.sh export \
  --realm onepass --file /tmp/onepass-realm.json
docker cp keycloak:/tmp/onepass-realm.json infra/keycloak/onepass-realm.json
```

이후 `docker-compose.yml`에 realm 자동 import 추가:
```yaml
keycloak:
  environment:
    KEYCLOAK_IMPORT: /opt/keycloak/data/import/onepass-realm.json
  volumes:
    - ./keycloak/onepass-realm.json:/opt/keycloak/data/import/onepass-realm.json
```

---

#### Task 4-2: End-to-End 인증 플로우 통합 테스트 【BE팀 / 2일】

```bash
# 테스트 시나리오 (순서대로 실행)

# 1. 비OIDC 인증 플로우 (현재 주 경로)
curl -X POST http://localhost:8083/api/v1/auth/broker \
  -H "Content-Type: application/json" \
  -d '{"agencyCode":"TEST","userId":"testuser","correlationId":"test-001"}'

# 2. CI 토큰 교환 (Phase 2 완료 후)
curl -X POST http://localhost:8083/api/v1/auth/ci-token \
  -H "Content-Type: application/json" \
  -d '{"ciPlaintext":"test-ci-value"}'

# 3. ext proxy 테스트 (Phase 2 완료 후)
curl -X GET http://localhost:8083/api/ext/clients

# 4. Keycloak OIDC 플로우 (Phase 3 완료 후)
curl http://localhost:8081/.well-known/openid-configuration
```

---

### Phase 5: 인프라 구성 (Day 12~20) — DevOps팀

**담당**: DevOps팀  
**완료 조건**: K8s 배포 가능 상태

---

#### Task 5-1: K8s Namespace 및 기본 리소스 정의 【DevOps / 1일】

**신규 파일**: `infra/k8s/namespace.yaml`
```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: onepass
  labels:
    app.kubernetes.io/name: onepass
    environment: production
```

**신규 파일**: `infra/k8s/secrets/secrets.yaml` (템플릿 — 실제 값은 Vault 또는 k8s Secret)
```yaml
# ⚠️ 이 파일은 템플릿입니다. 실제 secret값은 절대 커밋 금지.
# kubectl create secret generic 명령으로 생성하거나 Sealed Secrets 사용.
apiVersion: v1
kind: Secret
metadata:
  name: onepass-secrets
  namespace: onepass
type: Opaque
data:
  # base64 인코딩값 (echo -n "value" | base64)
  KEYCLOAK_CLIENT_SECRET: <base64>
  QIM_API_KEY: <base64>
  POSTGRES_PASSWORD: <base64>
  REDIS_PASSWORD: <base64>
  AES_SHARED_KEY: <base64>
```

---

#### Task 5-2: ido Deployment 및 Service 정의 【DevOps / 1일】

**신규 파일**: `infra/k8s/idem-hub/deployment.yaml`
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ido
  namespace: onepass
spec:
  replicas: 2
  selector:
    matchLabels:
      app: ido
  template:
    metadata:
      labels:
        app: ido
    spec:
      containers:
      - name: ido
        image: registry.smes.go.kr/onepass/ido:latest
        ports:
        - containerPort: 8083
        env:
        - name: SPRING_PROFILES_ACTIVE
          value: "prod"
        - name: QIM_API_KEY
          valueFrom:
            secretKeyRef:
              name: onepass-secrets
              key: QIM_API_KEY
        - name: KEYCLOAK_CLIENT_SECRET
          valueFrom:
            secretKeyRef:
              name: onepass-secrets
              key: KEYCLOAK_CLIENT_SECRET
        resources:
          requests:
            memory: "512Mi"
            cpu: "250m"
          limits:
            memory: "1Gi"
            cpu: "1000m"
        readinessProbe:
          httpGet:
            path: /actuator/health
            port: 8083
          initialDelaySeconds: 30
          periodSeconds: 10
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 8083
          initialDelaySeconds: 60
          periodSeconds: 30
---
apiVersion: v1
kind: Service
metadata:
  name: ido-service
  namespace: onepass
spec:
  selector:
    app: ido
  ports:
  - port: 8083
    targetPort: 8083
  type: ClusterIP
```

동일 패턴으로 아래 파일도 생성:
- `infra/k8s/idem-gate/deployment.yaml` (port: 8081)
- `infra/k8s/idem-registry/deployment.yaml` (port: 8082)

---

#### Task 5-3: Ingress 및 TLS 설정 【DevOps / 1일】

**신규 파일**: `infra/k8s/ingress.yaml`
```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: onepass-ingress
  namespace: onepass
  annotations:
    nginx.ingress.kubernetes.io/rewrite-target: /
    nginx.ingress.kubernetes.io/ssl-redirect: "true"
    cert-manager.io/cluster-issuer: "letsencrypt-prod"
spec:
  tls:
  - hosts:
    - onepass.smes.go.kr
    secretName: onepass-tls
  rules:
  - host: onepass.smes.go.kr
    http:
      paths:
      - path: /api/v1
        pathType: Prefix
        backend:
          service:
            name: ido-service
            port:
              number: 8083
      - path: /api/ext
        pathType: Prefix
        backend:
          service:
            name: ido-service
            port:
              number: 8083
      - path: /
        pathType: Prefix
        backend:
          service:
            name: frontend-service
            port:
              number: 80
```

---

#### Task 5-4: CI/CD 파이프라인 (GitHub Actions) 【DevOps / 2일】

**신규 파일**: `.github/workflows/deploy-prod.yml`
```yaml
name: Deploy to Production

on:
  push:
    branches: [main]

jobs:
  build-and-test:
    runs-on: ubuntu-latest
    steps:
    - uses: actions/checkout@v4

    - name: Set up JDK 17
      uses: actions/setup-java@v4
      with:
        java-version: '17'
        distribution: 'temurin'

    - name: Build and Test (BE)
      run: |
        DOCKER_UNAVAILABLE=true ./gradlew \
          :idem-common:test \
          :idem-hub:test \
          :idem-gate:test \
          --rerun-tasks

    - name: Build Docker Images
      run: |
        docker build -t ido:${{ github.sha }} ./ido
        docker build -t q-sign:${{ github.sha }} ./q-sign
        docker build -t q-im:${{ github.sha }} ./q-im

    - name: Push to Registry
      run: |
        echo ${{ secrets.REGISTRY_TOKEN }} | docker login registry.smes.go.kr -u ci --password-stdin
        docker push registry.smes.go.kr/onepass/ido:${{ github.sha }}
        docker push registry.smes.go.kr/onepass/q-sign:${{ github.sha }}
        docker push registry.smes.go.kr/onepass/q-im:${{ github.sha }}

  deploy:
    needs: build-and-test
    runs-on: ubuntu-latest
    steps:
    - name: Deploy to K8s
      run: |
        kubectl set image deployment/ido ido=registry.smes.go.kr/onepass/ido:${{ github.sha }} \
          -n onepass
        kubectl rollout status deployment/ido -n onepass --timeout=300s
```

---

### Phase 6: 부하 테스트 및 성능 검증 (Day 18~22) — DevOps + BE팀

#### Task 6-1: k6 부하 테스트 시나리오 검증 【1일】

```bash
# 기존 k6 시나리오 확인
find . -name "*.js" -path "*/k6/*" | head -10
find . -name "*.js" -path "*/load*" | head -10

# 없으면 기본 시나리오 작성
cat > infra/k6/auth-load-test.js << 'EOF'
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '1m', target: 20 },   // 일반 TPS (20 rps)
    { duration: '2m', target: 50 },   // 목표 TPS (50 rps)
    { duration: '1m', target: 100 },  // 버스트 TPS (100 rps)
    { duration: '1m', target: 0 },    // 감소
  ],
};

export default function () {
  const res = http.post('https://onepass.smes.go.kr/api/v1/auth/broker', JSON.stringify({
    agencyCode: 'TEST',
    userId: `user${Math.floor(Math.random() * 10000)}`,
    correlationId: `k6-${Date.now()}`,
  }), { headers: { 'Content-Type': 'application/json' } });

  check(res, {
    'status is 200': (r) => r.status === 200,
    'response time < 500ms': (r) => r.timings.duration < 500,
  });
  sleep(1);
}
EOF

k6 run infra/k6/auth-load-test.js
```

**목표 수치 확인**:
- 일반 TPS: 20~50 ✅ 통과
- 버스트 TPS: 100 ✅ 통과 (HikariCP 20 pool 기준)
- P95 응답시간: < 500ms
- 에러율: < 0.1%

---

## 3. 팀별 작업 분담 및 일정 요약

```
         Day 1   Day 2   Day 3   Day 4   Day 5   Day 6   Day 7   Day 8   Day 9  Day 10  ...  Day 20
FE팀     [P1: Task 1-1~1-4 보안 패치────────────][P2: Task 2-2 extInstance 교체──────────]
BE팀     [P1: Task 1-5]  [P2: Task 2-1 proxy][Task 2-3 ci-token]  [P3: q-sign OIDC──────────────]
DevOps   [P0: 환경 세팅]                                           [P4: Keycloak][P5: K8s────────]
         ↑ Phase 0 ↑Phase 1 완료(Day 3)        ↑Phase 2 완료(Day 8) ↑Phase 3 완료(Day 14)
```

---

## 4. 모듈별 남은 작업 상세 (코더 참조용)

### 4-1. onepass-fe 남은 작업 (완성률 30% → 목표 90%)

| # | 작업 | 파일 | 라인 | 예상일 |
|---|------|------|------|:---:|
| FE-01 | SKIP_AUTH 제거 | `Private.tsx` | 67, 109~117, 130, 136~144, 178, 182 | 0.5일 |
| FE-02 | AES_GCM_KEY DefinePlugin 제거 | `webpack.config.js` | 64 | 0.5일 |
| FE-03 | aesGcm.ts 파일 삭제 | `utils/crypto/aesGcm.ts` | 전체 | 0.1일 |
| FE-04 | MOCK 데이터 초기화 | `constants/mockData.ts` | 1~17 | 0.1일 |
| FE-05 | Math.random() → 서버 API | `Step5.tsx` | 72~77 | 0.5일 |
| FE-06 | extInstance.ts 삭제 | `api/extInstance.ts` | 전체 | 0.1일 |
| FE-07 | 16개 파일 beInstance 교체 | `api/ext/*.ts`, `api/provision/*.ts` | 각 import 1줄 | 1일 |
| FE-08 | ciToken.ts 완전 재작성 | `api/provision/ciToken.ts` | 전체 | 0.5일 |
| FE-09 | EXT_API_KEY DefinePlugin 제거 | `webpack.config.js` | 58 | 0.1일 |
| FE-10 | EXT_API_ENDPOINT DefinePlugin 제거 | `webpack.config.js` | 59 | 0.1일 |
| FE-11 | webpack proxy `/api/ext` 항목 제거 | `webpack.config.js` | 101~111 | 0.1일 |
| FE-12 | .env에서 EXT_API_KEY, EXT_API_ENDPOINT, AES_GCM_KEY 제거 | `.env` | - | 0.1일 |
| **합계** | | | | **~3.5일** |

### 4-2. ido 남은 작업 (완성률 68% → 목표 90%)

| # | 작업 | 파일 | 예상일 |
|---|------|------|:---:|
| IDO-01 | ExtProxyController 신규 구현 | `ext/ExtProxyController.java` (신규) | 1일 |
| IDO-02 | RestTemplateConfig 신규 | `config/RestTemplateConfig.java` (신규) | 0.1일 |
| IDO-03 | qim.base-url/api-key application.yml 추가 | `application.yml` | 0.1일 |
| IDO-04 | POST /api/v1/auth/ci-token 구현 | `AuthController.java` + DTO 2개 | 1일 |
| IDO-05 | QimAesEncryptor 구현 (CI 암호화) | 신규 또는 기존 Decryptor 역방향 | 0.5일 |
| IDO-06 | FeatureFlags F-11 retention dry-run 운영 검증 | `FeatureFlags.java` | 0.1일 |
| IDO-07 | HikariCP pool-size 운영 환경별 조정 | `application-prod.yml` | 0.1일 |
| IDO-08 | broker.mode: keycloak 전환 테스트 | `application.yml` | 0.5일 |
| **합계** | | | **~3.5일** |

### 4-3. q-sign 남은 작업 (완성률 40% → 목표 85%)

| # | 작업 | 파일 | 예상일 |
|---|------|------|:---:|
| QS-01 | OidcController 신규 구현 (5개 엔드포인트) | `api/OidcController.java` (신규) | 1.5일 |
| QS-02 | OidcProxyService 신규 구현 | `api/OidcProxyService.java` (신규) | 1일 |
| QS-03 | issueFromOidc() sub 기반 hash 수정 | `AuthServiceImpl.java:67` | 0.5일 |
| QS-04 | OidcAuthInput DTO에 sub 필드 추가 | DTO 파일 수정 | 0.5일 |
| QS-05 | strict-mode application.yml 명시 | `application.yml` | 0.1일 |
| QS-06 | RestTemplateConfig 추가 | `config/RestTemplateConfig.java` (신규) | 0.1일 |
| QS-07 | OIDC 플로우 통합 테스트 | `test/` | 1.5일 |
| **합계** | | | **~5.5일** |

### 4-4. 인프라 남은 작업 (완성률 25% → 목표 80%)

| # | 작업 | 파일 | 예상일 |
|---|------|------|:---:|
| INF-01 | K8s namespace.yaml | `infra/k8s/namespace.yaml` | 0.1일 |
| INF-02 | K8s secrets.yaml (템플릿) | `infra/k8s/secrets/` | 0.3일 |
| INF-03 | ido deployment.yaml + service.yaml | `infra/k8s/idem-hub/` | 0.5일 |
| INF-04 | q-sign deployment.yaml + service.yaml | `infra/k8s/idem-gate/` | 0.5일 |
| INF-05 | q-im deployment.yaml + service.yaml | `infra/k8s/idem-registry/` | 0.5일 |
| INF-06 | Ingress + TLS 설정 | `infra/k8s/ingress.yaml` | 0.5일 |
| INF-07 | Keycloak realm export + import 자동화 | `infra/keycloak/` | 0.5일 |
| INF-08 | CI/CD GitHub Actions | `.github/workflows/` | 2일 |
| INF-09 | ConfigMap (비밀이 아닌 설정값) | `infra/k8s/configmap.yaml` | 0.3일 |
| INF-10 | HPA (Horizontal Pod Autoscaler) | `infra/k8s/hpa.yaml` | 0.5일 |
| INF-11 | k6 부하 테스트 시나리오 정비 | `infra/k6/` | 1일 |
| **합계** | | | **~7일** |

---

## 5. 운영 배포 최소 조건 체크리스트 (Go/No-Go)

운영 배포 전 아래 체크리스트 **전항목 GREEN** 필수:

### 🔴 보안 (하나라도 RED = 배포 금지)
- [ ] **S-1**: `Private.tsx` SKIP_AUTH 코드 전체 제거 확인  
  `grep -r "SKIP_AUTH" idem-console/frontend/src` → 결과 없어야 함
- [ ] **S-2**: `webpack.config.js` DefinePlugin에서 AES_GCM_KEY, EXT_API_KEY 제거 확인  
  `grep -n "AES_GCM_KEY\|EXT_API_KEY" idem-console/frontend/webpack.config.js` → 결과 없어야 함
- [ ] **S-3**: JS 번들에 키값 미포함 확인  
  `strings idem-console/frontend/build/main.js | grep -i "aes\|api-key"` → 결과 없어야 함
- [ ] **S-4**: extInstance.ts 파일 삭제 확인  
  `ls idem-console/frontend/src/api/extInstance.ts` → No such file
- [ ] **S-5**: `Step5.tsx` Math.random() 코드 제거 확인  
  `grep -n "Math.random" idem-console/frontend/src/pages/ConversionSteps/member/Step5.tsx` → 결과 없어야 함
- [ ] **S-6**: Keycloak client secret `change-me` 제거 확인  
  `grep "change-me" infra/docker/docker-compose.yml infra/k8s/secrets/*.yaml` → 결과 없어야 함
- [ ] **S-7**: q-sign `strict-mode: true` 확인  
  `grep "strict-mode" idem-gate/src/main/resources/application*.yml`
- [ ] **S-8**: HTTPS 강제 (HTTP 리다이렉트 설정) 확인

### 🟡 기능 (하나라도 RED = 배포 금지)
- [ ] **F-1**: ido `/api/ext/**` proxy 정상 동작  
  `curl http://ido-host/api/ext/clients` → Q-IM 응답 정상
- [ ] **F-2**: CI 토큰 교환 BE 경유 확인  
  `curl -X POST http://ido-host/api/v1/auth/ci-token -d '{"ciPlaintext":"test"}'` → ciToken 반환
- [ ] **F-3**: q-sign OIDC Discovery 문서 확인  
  `curl http://q-sign-host/.well-known/openid-configuration` → JSON 반환
- [ ] **F-4**: Keycloak OIDC 플로우 End-to-End 테스트 통과
- [ ] **F-5**: 비OIDC 인증 플로우 통과 (`/api/v1/auth/broker`)
- [ ] **F-6**: QimSpReceiverController 3개 엔드포인트 동작 확인

### 🔵 성능 (목표치 미달 시 배포 재검토)
- [ ] **P-1**: 평균 TPS 50 이상 처리 (k6 테스트)
- [ ] **P-2**: 버스트 TPS 100 처리 (k6 테스트)
- [ ] **P-3**: P95 응답시간 500ms 이하
- [ ] **P-4**: 에러율 0.1% 이하

### 🟢 운영 준비 (배포 전 완료 필수)
- [ ] **O-1**: K8s 매니페스트 전체 apply 테스트 완료 (스테이징 환경)
- [ ] **O-2**: Keycloak realm JSON import 자동화 테스트
- [ ] **O-3**: Flyway 마이그레이션 스테이징 환경 검증 (V1~V13)
- [ ] **O-4**: `IDO_RETENTION_DRY_RUN=true` (기본값 확인 — 실수 파기 방지)
- [ ] **O-5**: 감사 로그 DB 저장 활성 (`IDO_AUDIT_DB_ENABLED=true`)
- [ ] **O-6**: 모니터링 대시보드 (Grafana) 구성 확인

---

## 6. 환경변수 완전 목록 (운영 배포 전 설정 필수)

### ido 환경변수

| 변수명 | 필수 여부 | 기본값 | 설명 |
|--------|:---:|------|------|
| `QIM_BASE_URL` | ✅ 필수 | `http://localhost:8082` | Q-IM 서비스 URL |
| `QIM_API_KEY` | ✅ 필수 | (없음) | Q-IM API 키 — Vault/K8s Secret |
| `KEYCLOAK_CLIENT_SECRET` | ✅ 필수 | `change-me` | Keycloak client secret — **반드시 교체** |
| `IDO_AUTH_RL_ENABLED` | 권장 | `true` | IP Rate Limit 활성화 |
| `IDO_RATE_LIMIT_ENABLED` | 권장 | `true` | 기관별 Rate Limit |
| `IDO_AUDIT_DB_ENABLED` | ✅ 필수 | `true` | 감사 로그 DB 저장 (운영 false 금지) |
| `IDO_AUDIT_KAFKA_ENABLED` | 권장 | `true` | 감사 로그 Kafka 발행 |
| `IDO_REDISSON_ENABLED` | ✅ 필수 | `true` | 분산 락 (멀티 Pod 필수) |
| `IDO_RETENTION_ENABLED` | 주의 | `false` | 개인정보 파기 스케줄러 |
| `IDO_RETENTION_DRY_RUN` | 주의 | `true` | 파기 dry-run (false 시 실제 삭제) |
| `IDO_OUTBOX_RELAY_ENABLED` | 권장 | `true` | Outbox Relay 스케줄러 |

### q-sign 환경변수

| 변수명 | 필수 여부 | 기본값 | 설명 |
|--------|:---:|------|------|
| `QSIGN_IDO_INTERNAL_SIG_STRICT_MODE` | ✅ 필수 | `true` | X-Internal-Sig strict 검증 |
| `SPRING_DATASOURCE_URL` | ✅ 필수 | `jdbc:postgresql://...` | q-sign DB (PostgreSQL) |
| `KEYCLOAK_ISSUER` | ✅ 필수 | - | Keycloak issuer URL |

### FE 환경변수 (운영 빌드용)

| 변수명 | 필수 여부 | 설명 |
|--------|:---:|------|
| `BE_API_ENDPOINT` | ✅ 필수 | ido URL (nginx 경유) |
| `NODE_ENV` | ✅ 필수 | `production` |
| ~~`SKIP_AUTH`~~ | ❌ 제거 | Phase 1에서 제거 |
| ~~`AES_GCM_KEY`~~ | ❌ 제거 | Phase 1에서 제거 |
| ~~`EXT_API_KEY`~~ | ❌ 제거 | Phase 1에서 제거 |
| ~~`EXT_API_ENDPOINT`~~ | ❌ 제거 | Phase 1에서 제거 |

---

## 7. 일정 및 공수 총합

| Phase | 기간 | FE팀 | BE팀 | DevOps | 합계 |
|-------|------|:---:|:---:|:---:|:---:|
| Phase 0: 환경 세팅 | Day 1 | 1일 | 1일 | 1일 | 1일 |
| Phase 1: 보안 패치 | Day 1~3 | 2일 | 0.5일 | - | 2.5일 |
| Phase 2: ext proxy + extInstance | Day 3~8 | 2일 | 3.5일 | - | 5.5일 |
| Phase 3: q-sign OIDC | Day 5~12 | - | 5.5일 | - | 5.5일 |
| Phase 4: Keycloak + 통합 테스트 | Day 10~14 | 1일 | 2일 | 1일 | 4일 |
| Phase 5: K8s 인프라 | Day 12~20 | - | 0.5일 | 7일 | 7.5일 |
| Phase 6: 부하 테스트 | Day 18~22 | - | 1일 | 1일 | 2일 |
| **총합** | **~22일** | **~6일** | **~14일** | **~10일** | **~30인일** |

> **보수적 산정**: 예상치의 1.5배 적용 시 **최대 45인일 (약 9주)**  
> **최소 팀 구성**: FE 1명 + BE 2명 (ido 전담 + q-sign 전담) + DevOps 1명

---

## 8. 알려진 기술 부채 (Phase 완료 후 다음 스프린트)

| # | 항목 | 위치 | 우선순위 |
|---|------|------|:---:|
| D-1 | KeycloakOidcService.java:140 "소셜 로그인 qimUserId 협의" 주석 | `KeycloakOidcService.java` | 🔴 |
| D-2 | `issueFromOidc()` 완전한 운영 검증 테스트 부재 | `AuthServiceImpl.java` | 🔴 |
| D-3 | MFA/AAL 스키마 (V12) 구현은 됐으나 비즈니스 로직 미연결 | `V12__add_mfa_aal_schema.sql` | 🟡 |
| D-4 | Handoff 키 로테이션 (F-12) dry-run 미검증 | `FeatureFlags.java` | 🟡 |
| D-5 | Webhook Outbox Relay (F-14) 엔드포인트 미검증 | - | 🟡 |
| D-6 | `business/Step5.tsx`에도 동일한 Math.random() 패턴 있는지 확인 필요 | `Step5.tsx (business)` | 🟡 |
| D-7 | agency-stub 서비스 운영 환경 제거 또는 비활성화 | `docker-compose.yml` | 🟡 |
| D-8 | q-sign V5 migration (auth_method) 운영 적용 전 검증 | `V5__add_auth_method.sql` | 🟡 |

---

*이 문서는 2026-05-13 소스코드 전수 분석 기준으로 작성되었습니다.*  
*코드 변경 시 해당 라인 번호가 달라질 수 있으므로 파일명과 함수명으로 위치 재확인 바랍니다.*
