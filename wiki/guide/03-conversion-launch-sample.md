# 가이드 03: 유관기관 오픈 시 OnePass 전환 URL 최종 샘플

| 항목 | 내용 |
|------|------|
| **문서 ID** | GUIDE-003 |
| **제목** | 유관기관 → OnePass 회원 전환 URL 최종 샘플 (기관 오픈 적용 기준) |
| **대상 독자** | 유관기관 개발팀 (연동 담당자) |
| **최종 갱신** | 2026-05-16 (v0.8.9) |
| **관련 문서** | [GUIDE-001](./01-agency-conversion-url-flow.md) · [GUIDE-002](./02-conversion-param-security.md) |

> **이 문서는 유관기관 개발팀이 실제 연동할 때 참조하는 샘플 모음입니다.**  
> 두 가지 방식을 모두 제공합니다:
> - **현재 방식 (레거시)**: 평문 쿼리스트링 — 보안 개선 전 호환용
> - **권장 방식 (신규)**: JWT Signed Request — [GUIDE-002](./02-conversion-param-security.md) 참조

---

## 1. 현재 방식 — 평문 파라미터 (레거시 호환)

> ⚠️ 현재 운영 중인 방식입니다. 보안 취약점이 존재하므로 신규 방식으로 전환을 권장합니다.

### URL 형식

```
https://onepass.smes.go.kr/conversion/step1
  ?redirect_uri={전환완료_후_기관URL}
  &mbrId={기관_회원_ID}
  &return_client={기관_client_id}
  &userType={ENT|IND}
```

### 케이스 A — 개인회원 전환 (bizinfo)

```
https://onepass.smes.go.kr/conversion/step1
  ?redirect_uri=https%3A%2F%2Fwww.bizinfo.go.kr%2Fmypage%2Fonepass-linked
  &mbrId=BIZ_USER_20240301_001234
  &return_client=sp-bizinfo
  &userType=IND
```

URL 인코딩 전 원문:
```
redirect_uri = https://www.bizinfo.go.kr/mypage/onepass-linked
mbrId        = BIZ_USER_20240301_001234
return_client= sp-bizinfo
userType     = IND
```

### 케이스 B — 기업회원 전환 (소상공인마당)

```
https://onepass.smes.go.kr/conversion/step1
  ?redirect_uri=https%3A%2F%2Fwww.sbiz.or.kr%2Fauth%2Fonepass-callback
  &mbrId=SBIZ_ENT_9876543210
  &return_client=sp-sbiz
  &userType=ENT
```

URL 인코딩 전 원문:
```
redirect_uri = https://www.sbiz.or.kr/auth/onepass-callback
mbrId        = SBIZ_ENT_9876543210
return_client= sp-sbiz
userType     = ENT
```

### 케이스 C — 회원유형 선택 허용 (기관이 유형 강제 안 할 때)

```
https://onepass.smes.go.kr/conversion/step1
  ?redirect_uri=https%3A%2F%2Fwww.fanfan.or.kr%2Fcallback
  &mbrId=FANFAN_USER_AB12CD34
  &return_client=sp-fanfan
```

`userType` 생략 시 → step1에서 사용자가 직접 개인/기업 선택

### 케이스 D — 스테이징 환경 (기관 개발 테스트)

```
https://onepass-staging.smes.go.kr/conversion/step1
  ?redirect_uri=https%3A%2F%2Fdev.bizinfo.go.kr%2Fcallback
  &mbrId=TEST_USER_001
  &return_client=sp-bizinfo-dev
  &userType=IND
```

---

## 2. 권장 방식 — JWT Signed Request (신규, 보안 강화)

> [GUIDE-002](./02-conversion-param-security.md) 구현 완료 후 적용하는 방식입니다.

### 기관 서버 측 JWT 생성 코드 샘플

#### Node.js (jsonwebtoken)

```javascript
const jwt = require('jsonwebtoken');

// OnePass에서 기관별로 발급한 API Key (K8s Secret으로 관리)
const API_KEY = process.env.ONEPASS_API_KEY;

const payload = {
    sub: 'BIZINFO_001',                              // 기관 코드 (OnePass 등록 기준)
    mbrId: 'BIZ_USER_20240301_001234',               // 기관 회원 ID
    redirectUri: 'https://www.bizinfo.go.kr/mypage/onepass-linked',
    returnClient: 'sp-bizinfo',                      // OnePass 등록 기관 client_id
    userType: 'IND',                                 // ENT | IND | (생략 가능)
    iat: Math.floor(Date.now() / 1000),              // 발급 시각
    exp: Math.floor(Date.now() / 1000) + 300,        // 5분 후 만료
    jti: require('crypto').randomUUID(),             // 재사용 방지 nonce
};

const signedRequest = jwt.sign(payload, API_KEY, { algorithm: 'HS256' });

// 최종 URL
const conversionUrl =
    `https://onepass.smes.go.kr/conversion/step1` +
    `?signed_request=${encodeURIComponent(signedRequest)}` +
    `&agency_code=BIZINFO_001`;

// 사용자를 conversionUrl로 302 리다이렉트
res.redirect(302, conversionUrl);
```

#### Java (Spring Boot, jjwt 라이브러리)

```java
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class OnePassConversionService {

    @Value("${onepass.api-key}")
    private String onepassApiKey;

    /**
     * OnePass 회원 전환 URL 생성
     *
     * @param mbrId       기관 회원 ID
     * @param redirectUri 전환 완료 후 돌아올 기관 URL
     * @param userType    "ENT" | "IND" | null
     * @return 전환 진입 URL
     */
    public String buildConversionUrl(String mbrId, String redirectUri, String userType) {
        Instant now = Instant.now();

        var key = Keys.hmacShaKeyFor(
                onepassApiKey.getBytes(StandardCharsets.UTF_8));

        var claimsBuilder = Jwts.claims()
                .subject("BIZINFO_001")           // ← 기관 코드로 변경
                .add("mbrId", mbrId)
                .add("redirectUri", redirectUri)
                .add("returnClient", "sp-bizinfo") // ← 기관 client_id로 변경
                .add("jti", UUID.randomUUID().toString());

        if (userType != null) {
            claimsBuilder.add("userType", userType);
        }

        String signedRequest = Jwts.builder()
                .claims(claimsBuilder.build())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(300))) // 5분
                .signWith(key)
                .compact();

        return "https://onepass.smes.go.kr/conversion/step1"
                + "?signed_request=" + URLEncoder.encode(signedRequest, StandardCharsets.UTF_8)
                + "&agency_code=BIZINFO_001";
    }
}
```

#### Python (PyJWT)

```python
import jwt
import uuid
import time
from urllib.parse import urlencode

ONEPASS_API_KEY = os.environ['ONEPASS_API_KEY']
AGENCY_CODE = 'BIZINFO_001'

def build_conversion_url(mbr_id: str, redirect_uri: str, user_type: str = None) -> str:
    now = int(time.time())
    payload = {
        'sub': AGENCY_CODE,
        'mbrId': mbr_id,
        'redirectUri': redirect_uri,
        'returnClient': 'sp-bizinfo',
        'iat': now,
        'exp': now + 300,  # 5분
        'jti': str(uuid.uuid4()),
    }
    if user_type:
        payload['userType'] = user_type

    signed_request = jwt.encode(payload, ONEPASS_API_KEY, algorithm='HS256')

    params = urlencode({
        'signed_request': signed_request,
        'agency_code': AGENCY_CODE,
    })
    return f'https://onepass.smes.go.kr/conversion/step1?{params}'
```

### 최종 URL 형태 (JWT 적용 후)

```
https://onepass.smes.go.kr/conversion/step1
  ?signed_request=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJCSVpJTkZPXzAwMSIsIm1icklkIjoiQklaX1VTRVJfMDAxIiwicmVkaXJlY3RVcmkiOiJodHRwczovL3d3dy5iaXpJbmZvLmdvLmtyL2NhbGxiYWNrIiwicmV0dXJuQ2xpZW50Ijoic3AtYml6aW5mbyIsInVzZXJUeXBlIjoiSU5EIiwiaWF0IjoxNzE2MTIzNDU2LCJleHAiOjE3MTYxMjM3NTZ9.서명값
  &agency_code=BIZINFO_001
```

---

## 3. 기관별 파라미터 등록 정보

기관 오픈 전 OnePass 운영팀에 아래 정보를 제출해야 합니다.

### 제출 양식

```yaml
# OnePass 기관 연동 등록 정보 (기관 제출용)
agency_code: BIZINFO_001           # OnePass에서 발급 (변경 불가)
official_name: "창업진흥원 비즈인포"
return_client: sp-bizinfo           # OnePass Keycloak에 등록된 client_id

# 허용할 callback URL 목록 (redirect_uri 검증용)
callback_whitelist:
  - "https://www.bizinfo.go.kr/mypage/onepass-linked"
  - "https://www.bizinfo.go.kr/auth/callback"
  - "https://dev.bizinfo.go.kr/callback"    # 개발환경 (선택)

# 지원 회원 유형
supported_user_types:
  - IND    # 개인회원
  # - ENT  # 기업회원 (필요 시 추가)

# 연동 방식
auth_type: API_KEY                  # API_KEY | HMAC | MTLS
# API Key는 OnePass가 발급하여 K8s Secret으로 안전하게 전달
```

### 기관별 `return_client` (client_id) 매핑 예시

| 기관명 | agency_code | return_client |
|---|---|---|
| 비즈인포 | `BIZINFO_001` | `sp-bizinfo` |
| 소상공인마당 | `SBIZ_001` | `sp-sbiz` |
| 중기부 | `MSS_001` | `sp-mss` |
| 기업마당 | `BIZMART_001` | `sp-bizmart` |
| 판판대로 | `FANFAN_001` | `sp-fanfan` |
| K-스타트업 | `KSTARTUP_001` | `sp-kstartup` |
| ... | ... | ... |

> **실제 `return_client` 값은 OnePass Keycloak 관리자에게 문의하십시오.**

---

## 4. 전환 완료 후 기관이 처리해야 할 것

전환 완료 후 `redirect_uri`로 사용자가 돌아오면, **기관 서버**에서 다음을 처리합니다.

### 4.1 Handoff Ticket 검증 (서버-서버 API)

```
[기관 브라우저]
    │  사용자 redirect_uri로 도달
    ↓
[기관 서버]
    │  POST https://ido.smes.go.kr/api/v1/handoff/verify
    │  Headers: X-Agency-Code: BIZINFO_001
    │           X-Api-Key: {기관 API Key}
    │  Body: { "ticketId": "{URL 파라미터로 받은 ticketId}" }
    ↓
    └─ 검증 성공 → { qimUserId, authLevel, authResultId, ... }
       → 기관 세션 생성 → 기관 서비스 이용 시작
```

### 4.2 redirect_uri에 ticketId 포함 여부

> OnePass가 `redirect_uri`로 리다이렉트할 때 `ticketId`를 쿼리 파라미터로 추가합니다.

```
# 기관이 받게 되는 최종 URL
https://www.bizinfo.go.kr/mypage/onepass-linked
  ?ticketId=HT-xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
  &status=success
```

> **주의**: `ticketId`는 **1회성**입니다. 기관 서버가 verify API를 호출하면 즉시 소비됩니다.  
> 브라우저에서 재로드하거나 뒤로가기 후 재시도 시 `TICKET_ALREADY_CONSUMED` 오류가 반환됩니다.

---

## 5. 환경별 Endpoint 정리

| 환경 | OnePass FE URL | OnePass IdO API URL |
|---|---|---|
| **운영** | `https://onepass.smes.go.kr` | `https://ido.smes.go.kr` |
| **스테이징** | `https://onepass-staging.smes.go.kr` | `https://ido-staging.smes.go.kr` |
| **개발** | `http://localhost:3000` (FE dev) | `http://localhost:8083` |

---

## 6. 체크리스트 (기관 오픈 전 확인 사항)

```
□ 1. OnePass 운영팀에 agency_code, return_client, callback_whitelist 등록 완료
□ 2. API Key 수령 및 기관 서버 K8s Secret 또는 환경변수에 안전하게 저장
□ 3. 전환 진입 URL 구성 코드 구현 완료 (레거시 또는 JWT 방식)
□ 4. redirect_uri URL 인코딩 처리 확인 (encodeURIComponent 적용)
□ 5. ticketId 수신 및 /api/v1/handoff/verify 호출 코드 구현 완료
□ 6. verify 성공 시 기관 세션 생성 로직 구현 완료
□ 7. 스테이징 환경 통합 테스트 완료 (개인회원 / 기업회원 각 1건)
□ 8. 운영 환경 smoke test 완료
```

---

> **이전 문서**: [GUIDE-002: 파라미터 보안](./02-conversion-param-security.md)  
> **다음 문서**: [GUIDE-004: 데이터 흐름 다이어그램](./04-conversion-data-flow-diagram.md)
