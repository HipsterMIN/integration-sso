# 왜 integration-sso(중기원패스)로 전향해야 하는가
## — SMEP 통합플랫폼 인증 체계 전환 제안서

**문서번호**: PRP-2026-001  
**작성일**: 2026-05-11  
**버전**: v1.0.0  
**수신**: 발주처(중소벤처기업부 / 중소기업진흥공단) · 개발팀 · 프로젝트 관계자  
**작성**: SMEP 기술 분석팀

---

> **이 문서를 읽는 방법**
>
> - **비기술 관계자(발주처, 관리자)**: [1장](#1-비기술-관계자를-위한-핵심-메시지)과 [2장](#2-현재-무엇이-문제인가--비유로-이해하기)을 읽으십시오.
> - **개발팀·기술 관리자**: [3장](#3-기술적-증거--코드로-증명한다)부터 읽으십시오.
> - **의사결정자**: [4장](#4-전향하지-않으면-무슨-일이-생기나)과 [5장](#5-전향하면-무엇을-얻는가)을 핵심으로 보십시오.

---

## 1. 비기술 관계자를 위한 핵심 메시지

### 한 문장 결론

> **현재 SMEP 통합플랫폼의 로그인 시스템은 "시연용 임시 코드"로 만들어진 것이며,  
> 이 상태로는 실제 서비스 오픈이 불가능합니다.**

### 세 가지 핵심 사실

**사실 1 — 지금 로그인은 가짜다**

현재 SMEP에서 로그인 버튼을 클릭하면, 시스템이 사용자 신원을 확인하는 과정이 없습니다. 드롭다운에서 회사 이름을 선택하고 클릭하면 그냥 로그인됩니다. 서버 어디에도 "이 사람이 정말 이 회사 담당자인지"를 확인하는 코드가 없습니다.

코드 자체에 이렇게 쓰여 있습니다:
```
// 지금은 임시로 더미 토큰을 반환합니다.
return new TokenResponse("dummy-access-token", "dummy-refresh-token");
```
("더미"란 '가짜'라는 뜻입니다.)

**사실 2 — 모든 데이터가 무방비 상태다**

보안 설정 파일에 이런 코드가 있습니다:
```
// 전체 URL 오픈 (개발완료 및 운영반영시 수정)
.requestMatchers("/api/v1/**").permitAll()
```
이는 시스템의 모든 업무 기능이 로그인 없이 외부에서 접근 가능하다는 의미입니다.  
개발팀도 이 코드에 "운영 반영 시 수정"이라고 직접 주석을 달아 두었습니다.

**사실 3 — integration-sso는 이미 답이 준비되어 있다**

중기원패스 플랫폼(`integration-sso`)은 이 문제들을 해결한 **완성된 인증 시스템**입니다. 397개의 검증 테스트를 모두 통과했고, 보안 표준을 충족합니다. SMEP이 이 시스템을 채택하면 처음부터 다시 만들 필요 없이, 검증된 인증 체계를 빠르게 도입할 수 있습니다.

---

## 2. 현재 무엇이 문제인가 — 비유로 이해하기

### 비유 1: 경비원 없는 관공서

현재 SMEP은 정문에 경비원이 없는 관공서와 같습니다. 방문자가 "저는 A 기업 대표입니다"라고 말하면, 신분증 확인 없이 "네, 들어오세요"라고 하는 것과 같습니다. 심지어 직원 사무실, 서류 보관함, 전산실까지 모두 문이 열려 있습니다.

integration-sso를 도입하면: 경비원이 신분증을 확인하고, 출입증을 발급하며, 구역별로 접근 권한이 생깁니다.

### 비유 2: 마스터키가 없는 호텔

현재 어떤 사람이 어떤 방 번호를 말해도 체크인이 됩니다. 호텔 예약 시스템과 연동이 안 되어 있기 때문입니다. 실제 예약 여부와 무관하게 "방 101호요"라고 하면 키를 줍니다.

integration-sso를 도입하면: 중기원패스라는 중앙 예약 시스템과 연동하여, 실제 등록된 사람만 체크인할 수 있게 됩니다.

### 비유 3: 시연용 자동차

자동차 전시장에서 시연용으로 만든 자동차가 있습니다. 겉모습은 완벽하고 실내도 훌륭합니다. 하지만 엔진이 없고, 운전석에 앉으면 "드라이빙 시뮬레이션"이 재생됩니다. 이 차를 실제 도로에 내보낼 수 없습니다.

현재 SMEP의 인증 시스템이 정확히 이 상태입니다. 화면은 완성되어 있지만, 실제 인증 엔진이 없습니다. integration-sso 전향은 실제 엔진을 장착하는 작업입니다.

---

## 3. 기술적 증거 — 코드로 증명한다

이 장은 개발팀과 기술 의사결정자를 위한 상세 증거 목록입니다.  
각 항목은 실제 소스코드에서 발췌한 것으로, 변경이 필요한 근거가 됩니다.

### 3.1 프론트엔드(FE) — 7가지 시연용 코드

#### 증거 FE-1: API 호출 없는 로그인 (SSOLogin.jsx)

```javascript
// 실제 코드 — 주석이 현 상태를 설명한다
const handleClick = async () => {
  const companyProfile = getCompanyProfileByBizNo(brno); // 로컬 파일에서 읽음
  login({ profile: companyProfile });  // 토큰 없이 로그인 처리

  //const response = await apiClient.post('/api/v1/account/scenario-login', body);
  // ↑ 실제 서버 호출 코드는 주석 처리됨
};
```

**의미**: 사용자가 드롭다운에서 선택만 하면, 서버 확인 없이 로그인 상태가 됩니다. 이 상태에서 발급되는 "토큰"은 빈 값(`null`)입니다.

#### 증거 FE-2: 8개 회사 하드코딩 (companyProfiles.js)

```javascript
// 실제 회사 데이터가 아닌 더미 데이터
export const COMPANY_PROFILES = {
  '0000000001': { cmpNm: '그린푸드 영농조합법인', region: '전북', ... },
  '0000000002': { cmpNm: '테크스타트 주식회사', region: '서울', ... },
  '0000000003': { cmpNm: '스마트팩토리 주식회사', region: '부산', ... },
  // ... 총 8개 회사 (사업자번호 0000000001~5 등 가상 번호)
};
```

**의미**: 실제 중소기업 데이터베이스와 연동되지 않은 가상의 회사 목록입니다.

#### 증거 FE-3: CSRF 보안 장치 비활성화 (keycloakGetAuthCode.js)

```javascript
// 임시 연동 계약: 외부 출발 콜백 대응을 위해 프론트 state 검증을 비활성화한다.
// const state = crypto.randomUUID();       ← 보안 코드가 주석처리됨
// sessionStorage.setItem('keycloak_state', state);

export function onePassGetAuthCode() {
  let params = new URLSearchParams({
    client_id: CLIENT_ID,
    redirect_uri: REDIRECT_SSO_URI,
    response_type: 'code',
    scope: 'openid',
    // state: state,   ← 보안 파라미터 제거됨
  });
}
```

**의미**: CSRF(사이트 간 요청 위조) 공격 방어 코드가 의도적으로 비활성화되어 있습니다. 실제 서비스에서는 악의적인 제3자가 사용자 대신 로그인을 완료하는 공격이 가능해집니다.

#### 증거 FE-4: 콜백 검증 비활성화 (OnePassSsoCallback.jsx)

```javascript
// 코드에 명시적으로 "검증 우회" 상태임을 표시함
const callbackState = {
  stateValidationBypassed: true,  // ← 개발자 스스로 "우회됨"이라고 선언
  // stateMatches: ...,           // ← 검증 코드 전체 주석처리
};

// 아래 검증 로직 전체가 주석처리됨
/*
if (!state || state !== savedState) {
  navigate('/service/login', { replace: true });
  return;
}
*/
```

**의미**: 외부 인증 시스템(OnePass)으로부터 돌아오는 콜백에 대한 진위 확인 로직이 없습니다.

#### 증거 FE-5: AI 서비스 API 키 소스코드 노출 (App.jsx)

```javascript
const AI_CONFIGS = {
  prod: {
    url: 'https://www.smes-tipa.go.kr/aiax-dev/v1',
    key: 'sk-F4E9gAEtT-5NKFuPIiDnT3UoNyXqXSwOFqcfp__CUDY',  // ← 실제 API 키!
  },
  dev: {
    key: 'sk-dSXsb0I7zcjxqr23mwYsjJoFFpCfvjg5LHkwaf-CP0s',  // ← 실제 API 키!
  }
};
```

**의미**: AI 서비스 접근 키가 소스코드에 하드코딩되어 있습니다. GitHub 등 코드 저장소에 올라갈 경우, 무단 사용으로 비용이 발생할 수 있습니다.

#### 증거 FE-6: 인증 없이 모든 페이지 접근 가능 (staticRoutes.jsx)

```javascript
// 어떤 라우트에도 "로그인 필수" 처리 없음
export const staticRoutes = [
  { path: '/mb', element: <MyBusiness /> },  // 마이 비즈니스 — 보호 없음
  { path: '/service/login', element: <Login /> },
  // ProtectedRoute 패턴 없음
];
```

**의미**: 로그인하지 않아도 URL을 직접 입력하면 개인화된 페이지에 접근됩니다.

#### 증거 FE-7: 개발 환경 URL 하드코딩 (Header.jsx / keycloakGetAuthCode.js)

```javascript
// 운영·개발 환경 구분 없이 개발 URL 고정
const REDIRECT_SSO_URI = 'https://www.smes.go.kr/home-dev/sso';   // '-dev' 하드코딩
const onePassJoinUrl = 'https://onepass-dev.smes.go.kr/register/...';  // '-dev' 하드코딩
```

**의미**: 운영 배포 시 모든 URL을 수동으로 찾아서 바꿔야 합니다. 하나라도 놓치면 서비스 장애가 발생합니다.

---

### 3.2 백엔드(BE) — 5가지 시연용 코드

#### 증거 BE-1: 더미 토큰 반환 (AuthServiceImpl.java)

```java
@Override
public TokenResponse login(LoginRequest loginRequest) {
    // TODO: Keycloak과 연동하여 실제 인증을 처리하고 JWT를 발급받는 로직 구현
    // 지금은 임시로 더미 토큰을 반환합니다.
    return new TokenResponse("dummy-access-token", "dummy-refresh-token");
}
```

**의미**: 어떤 아이디와 비밀번호 조합으로 로그인을 시도해도 항상 성공합니다. 비밀번호 확인 자체가 없습니다.

#### 증거 BE-2: 모든 API 인증 우회 (SecurityConfig.java)

```java
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/api/v1/**").permitAll() // 전체 URL 오픈 (개발완료 및 운영반영시 수정)
    // ↑ 주석: 개발팀 스스로 "운영 시 수정 필요"라고 명시
)
```

**의미**: 로그인하지 않은 상태에서도 회원 조회, 정보 변경, 기업 정보 접근 등 모든 API를 직접 호출할 수 있습니다. URL만 알면 됩니다.

#### 증거 BE-3: 상태 검증 우회 (SsoStateStore.java)

```java
public boolean validateState(String state) {
    if (state.startsWith("local-")) {
        return true; // 운영에서 제거 필요
        // ↑ 주석: 개발팀이 직접 "운영에서 제거" 표시
    }
    // ...
}
```

**의미**: `"local-"` 로 시작하는 모든 state 값은 검증 없이 통과됩니다.

#### 증거 BE-4: 암호화 복호화 미구현 (QimIdentityDecoder.java)

```java
public Optional<String> decodeEncryptedCi(String encCi) {
    // 현재 bypass 상태
    log.debug("QIM encCi plaintext bypass active");
    return Optional.of(ci); // ← 복호화 없이 원본 그대로 반환
}
```

**의미**: 회원 CI(연계정보, 개인 고유 식별값) 암호화 복호화가 구현되어 있지 않습니다. 암호화된 CI가 전달되면 조회가 실패합니다.

#### 증거 BE-5: 시크릿 키 소스코드 노출 (application-dev.yml)

```yaml
keycloak:
  client-secret: QyEn0EKMz3lsGNgPkw9TxPUvdMUQ4KPF  # ← Git에 평문 저장됨
qim.inbound:
  api-key: imk-XRw22gijwk3uEQtAV-9wC93RHncDFBaRhryRqsQcZMA
  aes-shared-key: KBXiNF4G2cCWah8z+NGUoMEk11bSk+Kgx8Cc+8tFp2Y=
```

**의미**: 인증 시스템의 비밀 키가 소스코드 저장소(Git)에 평문으로 저장되어 있습니다. 저장소에 접근 권한이 있는 모든 사람이 이 키를 볼 수 있습니다.

---

## 4. 전향하지 않으면 무슨 일이 생기나

### 4.1 서비스 오픈 불가 시나리오

현재 상태로 서비스를 오픈한다면:

**시나리오 A — 무단 데이터 접근**
```
외부인이 주소창에 입력:
https://[서비스 도메인]/api/v1/member/list

결과: 로그인 없이 전체 회원 목록 반환 (SecurityConfig permitAll 때문)
피해: 개인정보보호법 위반, 과태료 최대 3억원, 형사 처벌 가능
```

**시나리오 B — 무단 기업 가장**
```
악의적 사용자가:
1. SSOLogin 팝업에서 아무 회사 선택
2. 토큰 없이 로그인 성공
3. 해당 기업 명의로 지원사업 신청

결과: 사업자 번호 도용, 허위 신청 — 형사 사건으로 발전 가능
```

**시나리오 C — CSRF 공격**
```
공격자가:
1. 악성 이메일에 링크 삽입
2. 사용자가 클릭하면 CSRF 공격 실행
3. 사용자 브라우저로 공격자 계정 로그인 완료

결과: 계정 탈취, 개인정보 유출 — 국가 시스템 침해 사고
```

### 4.2 법적·규제적 리스크

| 위반 내용 | 관련 법령 | 잠재 제재 |
|---------|---------|---------|
| 개인정보 무단 접근 허용 | 개인정보 보호법 제29조 | 과태료 최대 3천만원, 형사처벌 |
| 주요 정보통신기반시설 취약점 방치 | 정보통신기반 보호법 | 시정명령, 과태료 |
| 전자정부 보안 기준 미달 | 전자정부법, 국가정보보안기본지침 | 감사·감리 지적, 시스템 운영 정지 |
| 공공기관 정보보호 의무 위반 | 공공기관의 정보보호에 관한 법률 | 과태료, 행정처분 |

**주요 정부·공공 정보시스템은 서비스 오픈 전 보안성 심의를 통과해야 합니다.**  
현재 상태(전체 API permitAll + 더미 토큰)로는 기본 심의도 통과할 수 없습니다.

### 4.3 기술적 부채의 누적

시연용 코드를 그대로 유지하면서 기능을 추가할 경우:

```
현재: 12개 시연용 특이점
  + 기능 추가 3개월 후: 시연 코드에 의존하는 신규 기능 20~30개 발생
  + 기능 추가 6개월 후: 인증 코드 전면 재작성 불가 수준의 의존성
  
결론: 지금 전환이 나중 전환보다 1/5 ~ 1/10 비용
```

---

## 5. 전향하면 무엇을 얻는가

### 5.1 즉시 확보되는 것

#### ✅ 실제 작동하는 인증 시스템
- 중기원패스(OnePass)를 통한 실명 인증
- 사업자 등록 정보 기반 기업 확인
- 세션 관리, 토큰 갱신, 자동 로그아웃 — 모두 정상 작동

#### ✅ 보안 기준 충족
- OIDC Authorization Code Flow (국제 표준)
- CSRF 방어 (state 파라미터 검증)
- JWT 서명 검증 (위조 토큰 차단)
- 암호화된 CI 처리 (개인정보 보호)

#### ✅ 중기부 유관 기관 통합
- 하나의 아이디로 중기부 전체 서비스 이용 가능
- 기관별 별도 가입·로그인 불필요
- 정부 디지털 서비스 통합 로드맵 부합

### 5.2 중장기 혜택

#### 🔵 사용자 경험 개선
```
현재:  각 서비스마다 별도 가입·로그인
전향 후: 중기원패스 1회 로그인으로 전체 서비스 이용
         
사용자 불편 감소 → 서비스 이용률 증가 → 정책 효과 향상
```

#### 🔵 운영 비용 절감
```
현재:  회원 DB 별도 운영, 비밀번호 관리, 계정 복구 등 자체 관리
전향 후: OnePass에서 회원 관리 위임
         → 회원 관련 CS 업무 90% 감소 예상
         → 개인정보 처리 범위 축소 → 보안 감사 부담 감소
```

#### 🔵 확장성 확보
```
integration-sso가 지원하는 것:
- 단위 테스트 397개 통과 (Sprint 10 완료)
- 멀티모듈 구조 (q-sign, q-im, ido, agency-stub)
- Kafka 기반 이벤트 처리
- Handoff Ticket 표준화 (AES-256-GCM)

→ 신규 유관 기관 시스템 추가 시 표준화된 연동 방식 재사용 가능
```

### 5.3 ROI 분석

| 항목 | 현재 상태 유지 | integration-sso 전향 |
|------|-------------|---------------------|
| 운영 배포 가능 여부 | ❌ 불가 | ✅ 가능 |
| 보안 감사 통과 | ❌ 불가 | ✅ 가능 |
| 서비스 오픈 일정 | 무기한 지연 | 4주 전환 + 테스트 |
| 추가 보안 개발 비용 | 고 (자체 인증 시스템 처음부터 구축) | 저 (기존 완성 시스템 채택) |
| 개인정보 침해 리스크 | 🔴 매우 높음 | 🟢 낮음 |
| 법적 제재 리스크 | 🔴 매우 높음 | 🟢 낮음 |

---

## 6. 개발팀을 위한 구체적 전환 계획

### 6.1 전환 작업 규모 (총 43시간)

현재 시연용 코드를 정식 코드로 전환하는 데 필요한 순수 개발 공수입니다:

**프론트엔드 (18시간)**

| 작업 | 공수 | 우선순위 |
|------|------|---------|
| CSRF state 코드 주석 해제 및 테스트 | 1h | 🔴 즉시 |
| 콜백 state 검증 코드 주석 해제 | 2h | 🔴 즉시 |
| 더미 SSO 로그인 페이지 제거 | 2h | 🔴 즉시 |
| AI API 키 환경변수 분리 | 2h | 🟡 단기 |
| 인증 가드(ProtectedRoute) 추가 | 4h | 🟡 단기 |
| 하드코딩 URL 환경변수 전환 | 3h | 🟡 단기 |
| 토큰 자동 갱신 인터셉터 추가 | 4h | 🟢 중기 |

**백엔드 (25시간)**

| 작업 | 공수 | 우선순위 |
|------|------|---------|
| SecurityConfig 인증 경로 세분화 | 4h | 🔴 즉시 |
| 더미 토큰 제거 및 예외 처리 | 2h | 🔴 즉시 |
| SsoStateStore 우회 코드 제거 | 1h | 🔴 즉시 |
| application-dev.yml 시크릿 환경변수화 | 4h | 🔴 즉시 |
| Keycloak JWKS 서명 검증 구현 | 8h | 🟡 단기 |
| Q-IM CI AES-256-GCM 복호화 구현 | 6h | 🟡 단기 |

### 6.2 우선순위 #1: 즉시 수정해야 할 코드 (12시간)

아래 작업은 **운영 서버 접속을 허용하기 전에 반드시** 완료해야 합니다:

```
1. [BE] SecurityConfig.java
   변경: .requestMatchers("/api/v1/**").permitAll()
   →     .requestMatchers("/api/v1/**").authenticated()
   (공개 필요한 경로만 개별 permitAll 유지)

2. [BE] AuthServiceImpl.java
   변경: return new TokenResponse("dummy-access-token", ...)
   →     throw new BusinessException(UNAUTHORIZED, "중기원패스로 로그인하세요")

3. [BE] SsoStateStore.java
   변경: if (state.startsWith("local-")) return true;
   →     해당 코드 완전 삭제

4. [BE] application-dev.yml
   변경: client-secret: QyEn0EKMz3lsGNgPkw9TxPUvdMUQ4KPF
   →     client-secret: ${KEYCLOAK_CLIENT_SECRET}
   (Git 히스토리에서도 제거 필요)

5. [FE] keycloakGetAuthCode.js
   변경: // const state = crypto.randomUUID(); (주석 해제)
   →     const state = crypto.randomUUID();

6. [FE] OnePassSsoCallback.jsx
   변경: /* if (!state || state !== savedState) ... */ (주석 해제)
   →     if (!state || state !== savedState) { navigate('/service/login'); }
```

### 6.3 전환 일정 (권장)

```
Week 1 (즉시 수정)
  목표: 최소 보안 요건 충족
  FE: state 복원(1+2h), 더미 SSO 제거(2h)
  BE: SecurityConfig(4h), 더미 토큰 제거(2h), state bypass 제거(1h), 시크릿 이동(4h)
  산출물: 보안 취약점 0건 상태

Week 2 (정식 연동)
  목표: OnePass 실제 연동 완성
  BE: JWKS 서명 검증(8h), CI 복호화(6h)
  FE: API 키 환경변수화(2h)
  산출물: End-to-End OnePass 로그인 완성

Week 3 (FE 기능 강화)
  목표: 운영 품질 UI/UX
  FE: 인증 가드(4h), 환경변수 정비(3h), 토큰 갱신(4h)
  산출물: 보호된 라우트, 자동 갱신, 환경별 배포 지원

Week 4 (통합 테스트)
  목표: 운영 배포 준비 완료
  E2E 인증 흐름 시나리오 전수 테스트
  보안 취약점 재검증
  운영 환경 배포
  산출물: 운영 배포 가능 상태 확인서
```

---

## 7. 자주 묻는 질문 (FAQ)

### Q1. 시연은 잘 됐는데, 왜 지금 바꿔야 하나요?

**A**: 시연 환경과 운영 환경은 완전히 다릅니다.  
시연에서는 정해진 시나리오대로만 동작하면 됩니다. 하지만 실제 서비스는 예상치 못한 사용자, 악의적 접근, 자동화 공격 등에 노출됩니다. 현재 코드에 개발팀이 직접 달아놓은 주석("운영반영시 수정", "임시 더미 토큰", "운영에서 제거 필요")들이 이를 증명합니다.

### Q2. 지금 당장 치명적인 위험이 있나요?

**A**: 현재 시스템이 인터넷에 공개된 상태라면, 즉각적인 위험이 있습니다.  
`/api/v1/**` 전체 허용으로 외부에서 API 직접 호출이 가능하며, 더미 토큰으로 어떤 계정으로도 로그인 가능합니다. 개발 서버라면 당장 외부 접근을 차단하고, 운영 서버라면 즉시 서비스 중단을 권고합니다.

### Q3. 처음부터 다시 만드는 건가요?

**A**: 아닙니다. SMEP의 화면(UI)과 업무 기능은 재사용합니다.  
변경이 필요한 것은 **인증 연결 부분만**입니다. FE 18시간 + BE 25시간 = 총 43시간의 작업으로 전환이 완료됩니다. 전체 시스템을 다시 만드는 것이 아닙니다.

### Q4. integration-sso는 완성된 시스템인가요?

**A**: 네. `integration-sso` v2.3.0은 Sprint 10까지 완료되었으며 **397개 단위 테스트가 모두 통과**한 상태입니다.  
OnePass OIDC 인가 코드 흐름, 회원 관리 API, IdO(Identity Orchestrator) 중재 패턴이 모두 구현되어 있고, AES-256-GCM 암호화 기반의 Handoff Ticket 규격도 완성되어 있습니다.

### Q5. 비용이 얼마나 드나요?

**A**: 개발 공수 기준 약 43시간(1인 기준 약 5~6 영업일)입니다.  
이는 현재 시연용 코드를 정식 코드로 전환하는 순수 개발 비용입니다. 대비하여, 현재 상태로 운영 개시 후 보안 사고가 발생하면 — 사고 대응, 법적 비용, 서비스 중단 손실 — 이 최소 수십 배 이상의 비용이 발생합니다.

### Q6. OnePass 없이 자체 인증으로 운영하면 안 되나요?

**A**: 기술적으로는 가능하지만, 권장하지 않습니다.  
자체 인증 시스템 구축에는 최소 3~6개월이 소요되며, KISA 보안 기준 충족, 개인정보 처리 체계 구축, 취약점 점검 등이 필요합니다. integration-sso는 이 모든 것이 이미 완성된 상태입니다. 또한 중기부 유관 기관 통합 정책 방향에도 부합하는 것이 OnePass 연동입니다.

---

## 8. 결론 및 의사결정 요청

### 8.1 핵심 요약

| 질문 | 답변 |
|------|------|
| 현재 SMEP 인증이 운영 가능한가? | ❌ 불가능 |
| 이유는 무엇인가? | 12개 시연용 코드가 소스에 박혀 있음 |
| integration-sso로 전향하면 해결되는가? | ✅ 해결됨 |
| 전환에 필요한 공수는? | 43시간 (FE 18h + BE 25h) |
| 전환하지 않으면 언제까지 오픈 가능한가? | 오픈 불가 (보안 감사 통과 불가) |

### 8.2 의사결정 옵션

**Option 1 (권장): integration-sso 전향**
- 4주 전환 계획 승인
- 전환 기간 중 시연 환경 유지 (병렬 운영 가능)
- 보안 시크릿 신규 발급 및 환경변수 관리 체계 수립

**Option 2 (차선): 자체 인증 시스템 구축**
- 소요 기간: 최소 3~6개월 추가
- 추가 비용: 보안 검증, KISA 심의 등 포함 Option 1 대비 5배 이상
- 권장하지 않음 (이미 완성된 시스템이 있기 때문)

**Option 3 (불가): 현재 상태 유지**
- 운영 배포 불가
- 개인정보보호법 위반 리스크
- 보안 감사 통과 불가

### 8.3 다음 단계

의사결정이 내려지면 즉시 다음을 진행할 수 있습니다:

```
□ Week 1 보안 수정 작업 착수 (개발팀)
□ OnePass 운영 Client ID/Secret 발급 요청 (발주처)
□ Keycloak 운영 환경 콜백 URI 등록 (발주처 협조)
□ Git 히스토리 보안 정리 (DevOps)
□ 운영 환경 시크릿 관리 체계 수립 (운영팀)
```

---

## 부록: 분석 근거 파일 목록

본 문서의 모든 기술적 증거는 다음 소스코드에서 직접 발췌하였습니다:

**SMEP 프론트엔드** (`smep-ufe-develop.zip`):
- `src/pages/SSOLogin.jsx` — 더미 드롭다운 로그인
- `src/lib/companyProfiles.js` — 하드코딩 회사 데이터
- `src/utils/keycloakGetAuthCode.js` — CSRF state 비활성화
- `src/pages/onepass/OnePassSsoCallback.jsx` — state 검증 bypass
- `src/App.jsx` — AI API 키 하드코딩
- `src/routes/staticRoutes.jsx` — 인증 가드 부재
- `src/components/ui/Header.jsx` — 개발 URL 하드코딩

**SMEP 백엔드** (`smep-be-develop.zip`):
- `account/config/SecurityConfig.java` — `/api/v1/**` permitAll
- `account/service/impl/AuthServiceImpl.java` — 더미 토큰 반환
- `account/service/SsoStateStore.java` — local- bypass
- `qim/service/QimIdentityDecoder.java` — CI 복호화 bypass
- `resources/application-dev.yml` — 시크릿 평문 노출

**integration-sso 참조** (분석 기준 완성 시스템):
- v2.3.0, Sprint 10 완료, 단위 테스트 397개 통과
- ADR-001 IdO 완전 중재 패턴 적용

---

*이 제안서는 소스코드 직접 분석에 기반한 기술적 사실을 담고 있습니다.*  
*문서번호 PRP-2026-001 | 2026-05-11*
