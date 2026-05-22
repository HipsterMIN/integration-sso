# 종합 분석 보고서 — 기획안 검토 및 8개 핵심 이슈

**문서 ID**: ANALYSIS-2026-0516-001  
**작성일**: 2026-05-16  
**대상**: integration-sso 프로젝트 PM / 아키텍트  
**기준 기획안**: `중기부통합회원_기획안_260515.pptx` (2026-05-13, 유큐브)  
**참조 코드 베이스**: `integration-sso` (현행), `onepass-be` / `onepass-fe` (외부, 읽기전용)

---

## 목차

1. [기획안 전체 검토 의견](#1-기획안-전체-검토-의견)  
2. [전환(Conversion) 프로세스 현황 분석](#2-전환-프로세스-현황-분석)  
3. [기관 선택 절차 폐기 — Step4 코드 변경 방향](#3-기관-선택-절차-폐기--step4-코드-변경-방향)  
4. [mbrId 평문 URL 파라미터 전달 — 표준 위배 분석](#4-mbrid-평문-url-파라미터-전달--표준-위배-분석)  
5. [보안 각성 전략](#5-보안-각성-전략)  
6. [두 프로젝트 표준 기준 설정 전략](#6-두-프로젝트-표준-기준-설정-전략)  
7. [유관기관 배포 SDK 명세 확인](#7-유관기관-배포-sdk-명세-확인)  
8. [자체 SSO 보유 기관 처리 패턴 권고](#8-자체-sso-보유-기관-처리-패턴-권고)

---

## 1. 기획안 전체 검토 의견

### 1.1 기획안이 잘 설계된 부분 (유지)

| 항목 | 평가 | 비고 |
|------|------|------|
| CI 값 기반 사용자 동기화 (슬라이드 8) | ✅ 옳음 | 식별자 정규화의 유일한 표준 방법 |
| 68개 기관 단일 허브 (슬라이드 8) | ✅ 옳음 | IdO 설계 의도와 일치 |
| 마이페이지 "유관기관 안내" (슬라이드 9) | ✅ 기관 선택 폐기 대체 | 장관 지시 수용 |
| 신규가입 5단계 흐름 (슬라이드 6) | ✅ 적절 | 현재 구현과 일치 |
| 만14세미만 법정대리인 동의 절차 (슬라이드 7) | ✅ 법률 의무 | 현재 FE **미구현** → 출시 전 필수 |
| 가입/전환 인증 vs 로그인 인증 구분 (슬라이드 14) | ✅ 보안 설계 원칙 | 인증 수단별 레벨 구분 명확화 |

### 1.2 기획안 미비·수정 필요 항목

#### ❗ CRITICAL: 만14세미만 전환 절차 미구현 (슬라이드 7)

기획안에 명시된 만14세미만 전환 흐름:
```
전환안내 → 유형선택 → 법정대리인 동의 → 보호자 인증 → 완료
                    ↑ 현재 구현 없음
```
- 「정보통신망법」 제31조: 만14세미만 개인정보 수집 시 법정대리인 동의 **의무**  
- 현재 `Step1.tsx`에 "14세 미만만 회원가입" 버튼만 존재하며, 클릭 시 아무 동작 없음  
- **결론**: 출시 전 별도 스프린트 계획 필수. 감리 항목.

#### ❗ HIGH: 슬라이드 14 인증 구분표 — 세부 기준 미확정

"가입/전환 인증"과 "로그인 인증"의 인증 수단별 허용 레벨이 기획안에 텍스트만 있고 매핑이 없음.  
권고 매핑:

| 인증 수단 | 가입/전환 (CI 발급) | 로그인 |
|-----------|-------------------|--------|
| 공동인증서 | L2 이상 허용 | L2 허용 |
| 간편인증(PASS, 카카오) | L1 허용 | L1 허용 |
| 휴대폰 본인인증 (NICE) | L1 허용 | L1 허용 |
| Any-ID | L1 이상 (기관 설정 따름) | L1 허용 |
| ID/PW | **금지** (CI 없음) | L0 (기본) |

#### ❗ HIGH: 슬라이드 7 "전환안내" 페이지 내용 미정의

기관 선택 폐기 후 "전환 대상 기관이 있다는 사실만 안내"가 어떤 정보를 보여줄지 기획안에 없음.  
**권고**: 전환 요청한 기관명 + 현재 OnePass에서 연결 가능한 기관 수를 표시.

#### ✅ MEDIUM: 슬라이드 9 "유관기관 안내" 메뉴 — 기획 의도 충분

기존 "기관 선택" 흐름을 마이페이지로 이동한 결정은 타당함.  
SSO의 핵심은 사용자가 의도적으로 기관을 선택하는 것이 아니라, 인증 후 자동으로 연결되는 것이기 때문.

---

## 2. 전환(Conversion) 프로세스 현황 분석

### 2.1 기획안 흐름 vs 현재 코드 vs OIDC 표준 3방향 비교

#### 기획안 (슬라이드 7): 전환 5단계
```
1단계: 전환안내 (return_client 기관명 표시)
2단계: 유형선택 (개인/기업)
3단계: 약관동의
4단계: 인증 (CI 기반)
5단계: 완료 → 자동 로그인
```

#### 현재 코드: Step1~Step8 (기획안과 매핑)
```
Step1 → [기획안 1단계+2단계] 유형선택 + URL 파라미터 검증
Step2 → [기획안 3단계]       약관동의 (MEMBER_CONVERSION flowContext)
Step3 → [기획안 4단계]       본인인증 (간편/NICE/공동인증)
Step4 → [기획안 없음]        ← 기관 선택 (장관 지시로 폐기 예정)
Step5 → [기획안 없음]        정보입력 + provisioning
Step6 → [기획안 없음]        전환 대상 기관 조회 (checkConversion)
Step8 → [기획안 5단계]       완료 + 리다이렉트
```

#### 심층 분석: Step3 → Step6 간 CI 처리 흐름
```
Step3 (인증):
  사용자 본인인증 완료
  → encryptCi(result.ci) // AES-256-GCM
  → exchangeCiToken(encryptedCi) // IdO → Q-IM
  → ciToken (JWT) 저장
  → mbrUuid 저장
  → history.push(Step4)

Step6 (전환 대상 조회):
  checkConversionProxy({ ciToken, mbrId, mbrUuid })
  → IdO ExtProxyController → Q-IM /check-conversion
  → perAgency[] 반환 (유관기관별 기존 계정 존재 여부)
  → ServiceListModal로 표시

Step5 (provisioning):
  provisionUser({ ciToken, loginId, clients: selectedClients })
  → IdO 경유 Q-IM /users
```

**핵심 발견**: **Step4가 Step6과 중복 목적**을 가짐  
- Step4: 가입 시 유관기관 선택 (신규가입용)  
- Step6: 전환 시 기존 계정 보유 기관 표시  
- 장관 지시("기관 선택 폐기")는 **Step4에 적용**, Step6은 "안내"로 변경

#### OIDC Authorization Code Flow와의 비교

| 항목 | OIDC 표준 | 현재 구현 | 평가 |
|------|-----------|-----------|------|
| 시작 신호 | `authorization_code` (Authorization Endpoint) | Handoff JWT (직접 URL 진입) | ⚠️ 비표준이나 단방향 흐름에서 수용 가능 |
| 사용자 식별자 전달 | 토큰 내 `sub` 클레임 | `mbrId` 평문 URL 파라미터 | ❌ 표준 위배 |
| 전환 완료 후 | 토큰 교환 (Authorization Code → Access Token) | `provisioningToken` → 자동 로그인 | ⚠️ Token 교환 단계 미완성 |
| 상태 관리 | `state` 파라미터 (CSRF 방지) | 없음 | ❌ CSRF 취약 |

---

## 3. 기관 선택 절차 폐기 — Step4 코드 변경 방향

### 3.1 장관 지시 해석

> "SSO인데 왜 기관을 선택하나?"

이 지시의 핵심은:
1. **전환 흐름에서 기관 체크박스 선택 UI 제거** (Step4의 현재 동작)
2. **대신 "전환 대상 기관이 있다는 사실"만 안내**
3. 기관 연결은 인증 완료 후 CI 기반으로 **자동** 처리

### 3.2 Step4 변경 범위

**기존 Step4 동작**:
- `getClients()` 호출 → 기관 목록 표시
- 체크박스로 원하는 기관 선택
- `data.selectedClients[]` 업데이트

**변경 후 Step4 동작** (전환 흐름):
- `getClients()` 호출 → 기관 목록 취득 (선택 없이)
- "전환 후 아래 기관과 연결됩니다" 안내 표시
- **모든 available 기관을 자동으로 selectedClients에 설정** (또는 selectedClients 개념 자체 제거)
- "다음" 버튼 → Step5로 이동

**Step5 provisioning 변경**:
- `clients` 파라미터: Step4에서 선택한 목록 → **ciToken 기반 자동 연결** (BE 처리)
- 또는 `clients: undefined` 전달 → BE가 CI 기준으로 자동 매핑

### 3.3 권고 구현 방향

```tsx
// Step4.tsx 변경 핵심 (기관 선택 → 안내)
function ConversionStep4({ memberType = 'member' }): JSX.Element {
  const { data, updateData } = useConversion();
  
  useEffect(() => {
    (async () => {
      const res = await getClients();
      if (res.statusCode === 200 && res.payload?.data?.clients) {
        const clients = res.payload.data.clients;
        updateData({ 
          availableClients: clients,
          // 기관 선택 폐기: 모든 기관을 자동으로 설정하지 않음
          // CI 기반 자동 연결은 BE(IdO)가 처리
          selectedClients: []  // 빈 배열 → provisioning 시 BE가 CI로 처리
        });
      }
    })();
  }, []);

  return (
    <ConversionLayout currentStep={4} ...>
      {/* 체크박스 UI 제거 */}
      <div className="text-info-wrap point">
        <strong>전환 후 아래 서비스와 연결됩니다</strong>
        <p>인증 완료 후 기존 계정과 자동으로 연결됩니다.</p>
        <ul>
          {data.availableClients.map(client => (
            <li key={client.ssoClientId}>{client.clientNm}</li>
          ))}
        </ul>
        <p className="note">
          연결된 서비스는 마이페이지 &gt; 유관기관 안내에서 확인할 수 있습니다.
        </p>
      </div>
    </ConversionLayout>
  );
}
```

### 3.4 BE 연계 — Q-IM 자동 연결 필요 여부 확인

Step4 선택 폐기 시 `provisionUser()` API의 `clients` 파라미터를 비워도 되는지 확인 필요:

```
현재 Step5:
  clients: data.selectedClients.map(...)  // 선택된 기관
  
변경 후:
  clients: undefined  // CI 기반 자동 연결 (BE에서 처리)
  또는
  clients: []  // 빈 배열 (기관 연결 없이 계정만 생성)
```

**⚠️ 주의**: Q-IM/IdO가 `clients=undefined`일 때 CI 기반 자동 기관 연결을 지원하는지 **BE팀 확인 필수**.  
현재 코드에서는 `clients`가 undefined면 기관 연결 없이 계정만 생성되는 것으로 보임.

### 3.5 Step6 "전환 대상 기관 조회" 역할 재정의

Step6은 `checkConversionProxy({ ciToken, mbrId, mbrUuid })`를 호출하여 유관기관별 **기존 계정 존재 여부**를 조회함.  
이 결과를 "선택"이 아닌 "안내" 용도로 재사용 가능:

```
기존: "어떤 기관 계정을 OnePass로 이전하시겠습니까?" (선택 UI)
변경: "아래 기관에 기존 계정이 있습니다. 전환 후 자동 연결됩니다." (안내 UI)
```

---

## 4. mbrId 평문 URL 파라미터 전달 — 표준 위배 분석

### 4.1 현재 URL 구조

```
https://onepass.smes.go.kr/conversion?
  redirect_uri=https://agency.smes.go.kr/callback&
  mbrId=leddkdkdk&          ← 사용자 ID 평문 노출
  return_client=AGENCY_A&   ← 클라이언트 식별자
  userType=IND
```

### 4.2 위배 사항 상세

#### OWASP A01 — 접근제어 취약점 관점
- `mbrId`는 유관기관이 OnePass에 저장된 회원 ID를 알고 있다는 의미
- 공격자가 임의 `mbrId`를 URL에 삽입하여 **다른 사용자의 계정으로 전환 시도** 가능
- **현재 방어**: Step3 CI 기반 본인인증으로 실제 소유자 검증 → **mbrId 자체의 신뢰성은 낮지만 최종 CI 검증이 방어**
- **문제**: 로그, 브라우저 히스토리, Referer 헤더에 `mbrId` 노출

#### RFC 6749 (OAuth 2.0) 관점
- 표준에서 `mbrId`에 해당하는 `login_hint` 파라미터는 존재하나 필수가 아님
- Authorization Request에 사용자 식별자 평문 포함은 비권장
- **표준 방식**: 서버 측에서 세션/토큰으로 사용자 식별

#### RFC 9700 (OAuth 2.0 BCP 2024) 관점
- URL 파라미터의 민감 데이터는 서버 로그에 기록되어 정보 유출 경로가 됨

### 4.3 Handoff JWT 대안이 이미 구현되어 있음

현재 프로젝트에는 이미 올바른 대안이 구현되어 있음:

```java
// HandoffController.java
// POST /api/v1/handoff/issue
// → HandoffTicket { ticketId: "hdp-xxxx", token: JWT, expiresAt }

// HandoffTicket JWT 내부 (서명됨):
{
  "sub": "qimUserId",    // 서버 측 feSession에서 추출 (P1 보안 수정)
  "agencyCode": "AGENCY_A",
  "return_client": "client-id",
  "callbackUrl": "https://agency.smes.go.kr/callback",
  "exp": 1716000000
}
```

**권고 전환 URL 구조**:
```
https://onepass.smes.go.kr/conversion?
  ticket=hdp-xxxx&    ← Handoff JWT (서명 + 암호화)
  userType=IND        ← 비민감 힌트만 URL에 포함
```

**전환 시작 흐름**:
```
유관기관 → POST /api/v1/handoff/issue (X-Agency-Code + X-Agency-Key)
         ← { ticketId: "hdp-xxxx", token: "eyJ..." }

유관기관 → 사용자 브라우저를
         → https://onepass.smes.go.kr/conversion?ticket=hdp-xxxx 로 리다이렉트

OnePass FE → GET /api/v1/handoff/verify (ticket=hdp-xxxx)
           ← { mbrId, agencyCode, returnClient, callbackUrl }
```

### 4.4 콜백 처리 영향 여부

> "콜백 처리에 무리 없는가?"

현재 `mbrId` URL 파라미터 방식도 Step3 CI 인증이 완료되면 **콜백 자체는 정상 동작**함.  
다만 다음 세 가지가 문제:

1. **브라우저 히스토리/로그에 mbrId 기록** → 민감정보 노출
2. **CSRF 방어 없음** → `state` 파라미터 부재 (공격자가 위조된 전환 URL 배포 가능)
3. **Handoff JWT 검증 없음** → 현재는 유관기관이 Handoff 발급 없이 직접 전환 URL 호출 가능

**단기 (현재 방식 유지 시 필수 보완)**:
```typescript
// Step1.tsx에 state 파라미터 검증 추가
const state = params.get('state') || '';
// state는 유관기관이 생성한 CSRF 토큰, 전환 완료 후 검증
```

**중기 (권고)**: Handoff JWT 방식으로 전환 — `mbrId` URL 제거

---

## 5. 보안 각성 전략

### 5.1 상황 파악

- **저쪽 팀**: H2 인메모리 DB(prod), IM API 주석 처리, AES-GCM 키 번들 포함, API Key 평문 `.env`
- **감리/감사 기준**: 행정안전부 "전자정부 SW 개발·운영 지침", NIST SP 800-53
- **이용자 규모**: 68개 유관기관, 실사용자 수만 명 예상

### 5.2 각성 접근 방법 — 효과 순위

#### 방법 1 (최고 효과): 공식 위험성 보고서 + 감리 항목 연계

ONEPASS-SEC-2026-003 문서를 **"사전 감리 점검 의견서"** 형태로 재포장:

```markdown
제목: [보안위험] OnePass BE 통합 불가 사유 — 사전 감리 점검 의견
수신: 프로젝트 PM, 감리단
발신: integration-sso 개발팀

# 주요 결함 (감리 기준 위배)

## F-01: 운영 DB로 H2 인메모리 사용 (BLOCKER)
- 행안부 지침 §4.2.1: 운영 환경 인메모리 DB 금지
- 재시작 시 전체 회원 데이터 소실
- 감리 코드: DB-001

## F-02: IM API 주석 처리로 회원 동기화 단절 (BLOCKER)
- 회원 가입 처리되나 Q-IM에 동기화되지 않음
- 로그인 불가 상태로 서비스 불가
- 감리 코드: LOGIC-001

## F-03: AES-GCM 키 Webpack 번들 포함 (BLOCKER)
- OWASP A02: Cryptographic Failures
- 브라우저에서 `window.__webpack_exports__` 접근 시 키 노출
- CI 암호화 무력화 = 개인정보보호법 제29조 위반
- 감리 코드: CRYPTO-001
```

#### 방법 2 (중간 효과): 기술 데모 — 실제 취약점 재현

H2 인메모리 DB 재시작 데이터 소실을 로컬에서 직접 시연:
```bash
# onepass-be 실행 후 회원가입
# → 애플리케이션 재시작
# → 로그인 시도 → "사용자 없음" 오류
# → "prod 환경에서 서비스 재시작 = 전체 회원 데이터 소실"
```

#### 방법 3 (장기 효과): 표준 준수 체계 확립

integration-sso가 "표준"이 되면, 저쪽 팀 코드는 자연히 비표준으로 분류됨:
1. integration-sso 기준으로 **API 계약서** 작성
2. 감리단에 integration-sso 설계서 제출
3. 저쪽 팀에 "표준 API 계약 준수" 요구

### 5.3 공식 채널 활용

```
감리단 → 감리 항목에 DB/암호화/API Key 보안 포함 요청
PM    → "저쪽 팀 코드 감리 통과 조건" 명문화 요청
개발팀 → ONEPASS-SEC-2026-003 문서 공식 배포
         (내부 이슈 트래커 티켓 생성 + 담당자 지정)
```

---

## 6. 두 프로젝트 표준 기준 설정 전략

### 6.1 현황

| 항목 | integration-sso (현행) | onepass-be (저쪽 팀) |
|------|----------------------|-------------------|
| DB | PostgreSQL (운영용) | H2 인메모리 (운영 불가) |
| 회원 동기화 | IdO → Q-IM (정상) | IM API 주석 처리 (단절) |
| CI 암호화 | 서버 측 AES-GCM (키 안전) | FE 번들에 키 포함 (위험) |
| 클러스터 지원 | Redis 기반 (가능) | 단일 JVM 인메모리 (불가) |
| OIDC 표준 | Q-Sign (Keycloak 기반) | 자체 구현 (미완성) |
| 감리 통과 가능성 | 높음 | **낮음** |

### 6.2 표준화 전략

#### 단계 1: 사실 선언 (즉시 실행)

integration-sso를 **"공식 표준 구현체"**로 PM/감리단에 공식 선언.  
근거: 이미 완성도 높은 설계서, 감리 기준 충족, Q-Sign/Q-IM/IdO 정합성.

#### 단계 2: API 계약 주도권 확보

integration-sso의 IdO API 스펙을 **공식 API 명세서**로 등록:
- `/api/v1/handoff/issue` — Handoff 발급 표준
- `/api/v1/ext/**` — 유관기관 API 표준
- `/api/v1/auth/**` — 인증 API 표준

저쪽 팀의 `/api/v1/ext/**` 경로는 **동일 경로 충돌** (ProxyController) → 표준 명세 위반으로 판정 가능.

#### 단계 3: 통합 타임라인 설정

```
Phase 1 (즉시): onepass-be 코드 → 감리단 제출 금지
Phase 2 (4주): onepass-be → integration-sso 표준 API 준수 방향으로 리팩터링
Phase 3 (8주): 하나의 코드베이스로 통합 (저쪽 팀이 integration-sso에 기여하는 형태)
```

### 6.3 핵심 메시지

> "onepass-be는 integration-sso의 일부 기능을 재구현하려 했으나, 미완성 상태이고 치명적 결함이 있습니다. 두 프로젝트를 병행하면 혼란만 가중됩니다. integration-sso를 표준으로 삼고, 필요한 기능은 integration-sso에 기여하는 방식으로 진행하십시오."

---

## 7. 유관기관 배포 SDK 명세 확인

### 7.1 클라이언트 패턴별 SDK 요구사항

현재 AgencyMeta의 `integrationType` 필드 기준 4가지 패턴:

```
DIRECT         → SDK 필요 (API 호출 직접 구현)
APACHE_GATE    → Apache 모듈 설정 가이드
BRIDGE         → Bridge 서버 배포 가이드  
INTERNAL_SSO   → 자체 SSO 연동 가이드
```

### 7.2 DIRECT 패턴 SDK 명세 (표준 가이드)

유관기관이 구현해야 하는 최소 SDK:

```
[SDK 구성 요소]

1. 인증 시작 (Handoff Issue)
   POST {IDO_HOST}/api/v1/handoff/issue
   Header: X-Agency-Code: {AGENCY_CODE}
           X-Agency-Key: {AGENCY_KEY}
           Idempotency-Key: {UUID}  // 재시도 안전
   Body: {
     "agencyCode": "{AGENCY_CODE}",
     "authResultId": "{AUTH_RESULT_ID}",
     "authLevel": "L1",
     "providerCode": "NICE_PHONE",
     "callbackUrl": "https://agency.go.kr/onepass/callback"
   }
   Response: { "ticketId": "hdp-xxx", "token": "eyJ...", "expiresAt": 1716000000 }

2. OnePass 전환 URL로 리다이렉트
   https://onepass.smes.go.kr/conversion?ticket={ticketId}&userType=IND

3. 콜백 수신 (Callback Endpoint)
   GET https://agency.go.kr/onepass/callback
   Params: ?onepass_token={token}&state={state}
   → onepass_token 검증: POST {IDO_HOST}/api/v1/handoff/verify
     Header: X-Agency-Code: {AGENCY_CODE}
     Body: { "ticketId": "{ticketId}" }
   Response: {
     "mbrId": "{사용자ID}",
     "agencyCode": "{기관코드}",
     "attributes": { ... }
   }

4. 멱등성 처리
   - 동일 Idempotency-Key로 재요청 시 동일 ticketId 반환
   - 네트워크 오류 시 동일 키로 재시도 안전

5. 오류 코드
   IDO_SESSION_NOT_FOUND   → 인증 세션 없음 (재로그인 안내)
   IDO_TICKET_EXPIRED      → 티켓 만료 (재시도 안내)
   IDO_CALLBACK_MISMATCH   → 콜백 URL 불일치 (기관 등록 정보 확인)
   IDO_AUTH_LEVEL_REQUIRED → 최소 인증 수준 미충족
```

### 7.3 Apache mod_auth_openidc 패턴 설정 가이드

```apache
# /etc/apache2/conf.d/oidc.conf
OIDCProviderMetadataURL https://onepass.smes.go.kr/realms/smeg/.well-known/openid-configuration
OIDCClientID agency-a-client
OIDCClientSecret {KEYCLOAK_CLIENT_SECRET}
OIDCRedirectURI https://agency-a.go.kr/onepass/callback
OIDCCryptoPassphrase {RANDOM_32BYTE_HEX}

OIDCScope "openid profile"
OIDCRemoteUserClaim preferred_username
OIDCInfoHook userinfo

# OnePass 전용: Handoff 발급 후 Keycloak으로 라우팅
OIDCStateCallbackURL https://onepass.smes.go.kr/auth/realms/smeg/protocol/openid-connect/auth
```

**주의**: Apache 패턴은 Keycloak(Q-Sign)을 OIDC 엔드포인트로 직접 사용.  
Handoff 흐름 없이 직접 Keycloak Authorization Code Flow 사용 → **전환(Conversion) 기능은 별도 처리 필요**.

### 7.4 Bridge 패턴 SDK (경량 서버 배포)

유관기관이 자체 서버에 배포하는 경량 Bridge:

```
[Bridge 최소 구현 요건]
- GET  /bridge/login       → OnePass 인증 요청 시작 (Handoff Issue)
- GET  /bridge/callback    → OnePass 인증 완료 수신
- POST /bridge/logout      → 세션 종료
- GET  /bridge/healthcheck → 상태 확인

[기술 요건]
- HTTPS 필수 (인증서 유효)
- 세션 저장소: Redis 또는 DB (인메모리 금지)
- 타임아웃: 30초 이내 응답
- 로깅: 인증 이벤트 90일 보관 (행안부 지침)
```

---

## 8. 자체 SSO 보유 기관 처리 패턴 권고

### 8.1 자체 SSO가 가장 어려운 이유

```
자체 SSO 보유 기관의 문제:
1. 기관 내부 사용자는 이미 기관 SSO로 로그인 → OnePass 계정 별도 생성 부담
2. 세션 동기화: OnePass 로그아웃 → 기관 SSO도 로그아웃 (Single Logout)
3. 사용자 매핑: OnePass mbrId ↔ 기관 내부 userId 매핑 테이블 필요
4. 신뢰 방향: OnePass가 IdP, 기관 SSO는 SP? 또는 반대?
```

### 8.2 권고 패턴: INTERNAL_SSO — OnePass를 상위 IdP로

```
[인증 흐름]
기관 사용자 → 기관 SSO 포털 접근
기관 SSO → OnePass에 OIDC 위임 (기관 SSO가 SP, OnePass가 IdP)
          → Keycloak Identity Brokering 설정
OnePass → 기관 사용자 인증 (기존 OnePass 계정 기준)
OnePass → 기관 SSO에 OIDC 토큰 발급
기관 SSO → 내부 세션 발급

[장점]
- 기관 사용자 입장: 기관 SSO 화면 그대로 사용
- OnePass 입장: CI 기반 사용자 매핑 한 번에 처리
- 관리: Keycloak Identity Provider 설정만으로 구성 가능
```

### 8.3 Keycloak Identity Brokering 설정 (Q-Sign 기반)

```json
// Keycloak Admin API: Identity Provider 추가
{
  "alias": "agency-internal-sso",
  "providerId": "oidc",
  "enabled": true,
  "config": {
    "clientId": "onepass-broker",
    "clientSecret": "{AGENCY_SSO_CLIENT_SECRET}",
    "authorizationUrl": "https://agency-sso.go.kr/auth",
    "tokenUrl": "https://agency-sso.go.kr/token",
    "userInfoUrl": "https://agency-sso.go.kr/userinfo",
    "defaultScope": "openid profile",
    "syncMode": "IMPORT"
  }
}
```

### 8.4 Single Logout 처리 (가장 어려운 부분)

```
[SLO 흐름]
사용자 → OnePass 로그아웃 요청
OnePass (Keycloak) → Back-channel Logout 발송 (RFC 8414)
                   → 기관 SSO의 /backchannel_logout 호출
기관 SSO → 내부 세션 종료

[구현 요건]
- 기관 SSO가 OIDC Back-channel Logout 지원 필요
- `logout_token` (JWT) 수신 및 검증 구현
- 미지원 기관: Front-channel Logout (iframe 방식) fallback
```

**AgencyMeta의 `INTERNAL_SSO` 타입과 `ssoDomain` 필드가 이미 설계되어 있음** →  
`ssoDomain` 필드를 기관 SSO의 OIDC 메타데이터 URL로 확장 권고.

### 8.5 자체 SSO 기관 연동 우선순위 결정 기준

| 기준 | 설명 | 가중치 |
|------|------|--------|
| OIDC/OAuth 2.0 표준 지원 여부 | 지원 시 Keycloak Identity Brokering 즉시 가능 | 높음 |
| Single Logout 지원 여부 | Back-channel 지원 시 완전한 SLO 가능 | 높음 |
| 사용자 규모 | 대규모 기관 우선 연동 | 중간 |
| 레거시 시스템 여부 | SAML 2.0만 지원 시 별도 Bridge 필요 | 낮음 |

**SAML 2.0 전용 기관 처리**:
```
기관 SAML SP → Keycloak SAML Identity Provider Mapper
             → OnePass OIDC로 변환
             (Keycloak이 SAML ↔ OIDC 변환 처리)
```

---

## 종합 권고 — integration-sso 완성 로드맵

### 즉시 실행 (이번 스프린트)

| 우선순위 | 항목 | 담당 |
|---------|------|------|
| P0 | Step4 기관 선택 → 안내 UI 변경 (기획안 반영) | FE |
| P0 | ONEPASS-SEC-2026-003 공식 배포 (감리단 제출) | PM |
| P1 | mbrId URL 파라미터 → Handoff JWT로 전환 계획 수립 | BE+FE |
| P1 | 만14세미만 전환 절차 스프린트 계획 수립 | PM+FE |
| P2 | SDK 명세서 v1.0 작성 완료 | 아키텍트 |

### 단기 (다음 2 스프린트)

| 우선순위 | 항목 |
|---------|------|
| P1 | Handoff JWT 기반 전환 URL 구조로 전환 |
| P1 | `state` 파라미터 CSRF 방어 추가 |
| P2 | INTERNAL_SSO 패턴 Keycloak 설정 가이드 완성 |
| P2 | APACHE_GATE 패턴 Apache 설정 가이드 완성 |

### 중기 (Sprint 17 전후)

| 우선순위 | 항목 |
|---------|------|
| P1 | ProvisioningServiceImpl K8s Secret 조회 구현 (REQUIRES_MANUAL 해소) |
| P1 | Feature Flag F-20, F-23, F-26 활성화 |
| P2 | 만14세미만 법정대리인 동의 절차 구현 |

---

> **작성자 의견**: integration-sso는 설계 원칙, 보안 수준, 확장성 모두에서 onepass-be보다 우월합니다. 이 프로젝트를 표준으로 삼는 결정은 올바릅니다. 기획안의 핵심 변경(기관 선택 폐기, 마이페이지 유관기관 안내)도 SSO 원칙에 부합합니다. 가장 위험한 미구현 항목은 **만14세미만 법적 의무**이므로 이를 최우선 추적하십시오.
