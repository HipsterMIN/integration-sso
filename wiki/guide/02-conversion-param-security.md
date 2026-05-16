# 가이드 02: 전환 URL 파라미터 보안 — 암호화 대안 및 수정 구현

| 항목 | 내용 |
|------|------|
| **문서 ID** | GUIDE-002 |
| **제목** | 회원 전환 URL 파라미터 보안 강화 — JWT Signed Request 방식 도입 |
| **대상 독자** | OnePass FE/BE 개발팀, 유관기관 연동 담당자, 보안 검토자 |
| **최종 갱신** | 2026-05-16 (v0.8.9) |
| **관련 문서** | [GUIDE-001](./01-agency-conversion-url-flow.md) · [GUIDE-003](./03-conversion-launch-sample.md) |
| **수정 파일** | `Step1.tsx` · `Step8.tsx` · `application.yml` · `ConversionInitController.java` (신규) |

---

## 1. 현재 파라미터 보안 문제

### 1.1 공격 시나리오

현재 진입 URL의 모든 파라미터는 평문 쿼리스트링으로 노출됩니다.

```
https://onepass.smes.go.kr/conversion/step1
  ?redirect_uri=https://evil.com/steal-token   ← 피싱 URL 주입
  &mbrId=OTHER_USER_123                        ← 타 회원 ID 위조
  &return_client=sp-admin                      ← 관리자 기관 client_id 위조
  &userType=IND
```

| 공격 | 가능 여부 | 현재 방어 |
|---|---|---|
| `redirect_uri` 피싱 URL 주입 | ⚠️ 가능 | Layer 1 `isSafeRedirectUri()` — **현재 버그로 우회됨** |
| `mbrId` 타 회원 ID 주입 | ⚠️ 가능 | 방어 없음 (Step6 `checkConversionProxy`에서 CI 불일치 시 차단) |
| `return_client` 위조 | ⚠️ 가능 | Handoff 발급 시 `agency_meta` 검증에서 차단 |

### 1.2 암호화해야 할 파라미터

| 파라미터 | 이유 |
|---|---|
| `mbrId` | 기관 회원 ID — 타 회원 ID로 교체해도 서버가 즉시 감지하지 못함 |
| `redirect_uri` | 악의적 URL 주입 후 Layer 1 버그 악용 시 피싱 가능 |
| `return_client` | 타 기관 client_id로 위조 시 Handoff Ticket 귀속 기관 조작 가능 |

---

## 2. 가장 일반적인 방법 — JWT Signed Request

> **선택 근거**: OAuth 2.0 / OIDC 생태계에서 표준으로 사용하는 방식.  
> 유관기관이 이미 API Key를 보유하고 있으므로 추가 키 교환 없이 HMAC-SHA256 서명 적용 가능.  
> AES 암호화보다 구현이 단순하고 검증이 명확합니다.

### 2.1 방식 비교

| 방식 | 특징 | 채택 |
|---|---|---|
| **JWT HS256 Signed Request** | 기관 API Key로 서명, 파라미터 변조 감지, 표준 라이브러리 | ✅ **채택** |
| AES-GCM 암호화 쿼리스트링 | 파라미터 내용 은닉, 키 관리 복잡, 비표준 | 선택지 |
| PKCE + state 파라미터 | OAuth 2.0 표준, code 교환 필요, 기관 서버 추가 구현 | 중장기 |
| 서버-to-서버 세션 등록 | 가장 안전, 기관 BE 구현 필수, 라운드트립 추가 | 중장기 |

### 2.2 전체 흐름

```
[기관 서버] ──────────────────────────────────────────────────────────
  1. JWT 페이로드 구성
     {
       "sub": "AGENCY_CODE",          // 기관 코드
       "mbrId": "AGENCY_USER_123",    // 기관 회원 ID
       "redirectUri": "https://www.bizinfo.go.kr/callback",
       "returnClient": "sp-bizinfo",
       "userType": "IND",
       "iat": 1716123456,             // 발급 시각 (epoch seconds)
       "exp": 1716123756,             // 만료 (5분 후)
       "jti": "uuid-v4-nonce"        // 재사용 방지 (선택)
     }

  2. HMAC-SHA256 서명 (기관 API Key로)
     signed_request = JWT.sign(payload, apiKey, { algorithm: 'HS256' })

  3. 리다이렉트 URL 생성
     https://onepass.smes.go.kr/conversion/step1
       ?signed_request={JWT}
       &agency_code=BIZINFO_001       // 서명 검증용 기관 식별자 (공개)

[OnePass Step1.tsx] ────────────────────────────────────────────────
  4. signed_request + agency_code 추출
  5. POST /api/v1/conversion/init { signedRequest, agencyCode }

[OnePass BE: ConversionInitController] ─────────────────────────────
  6. agency_meta에서 agencyCode로 api_key_hash 조회
  7. JWT 서명 검증 (HMAC-SHA256)
  8. exp 유효성 검증 (5분 이내)
  9. jti Redis 중복 체크 (선택 — 재사용 방지)
  10. redirectUri를 agency_meta.callback_whitelist로 검증
  11. 검증된 ConversionSession 발급 → sessionToken 반환

[OnePass Step1.tsx] ────────────────────────────────────────────────
  12. sessionToken을 ConversionContext에 저장
  13. step2로 이동 (이후 모든 API에 sessionToken 포함)
```

---

## 3. 구현 — BE: ConversionInitController (신규)

```java
// ido/src/main/java/kr/go/smes/ido/conversion/ConversionInitController.java

@Slf4j
@RestController
@RequestMapping("/api/v1/conversion")
@RequiredArgsConstructor
public class ConversionInitController {

    private final ConversionInitService conversionInitService;

    /**
     * 유관기관 서명된 전환 요청 검증 — signed_request JWT 검증 후 ConversionSession 발급
     *
     * <p>기관이 구성한 JWT(HS256)를 검증하고, 검증된 파라미터로 ConversionSession을 생성합니다.
     * Step1.tsx가 호출하며, 반환된 conversionSessionId를 이후 모든 단계에서 사용합니다.
     *
     * @param req signed_request(JWT) + agencyCode
     * @return conversionSessionId (Redis TTL: 30분)
     */
    @PostMapping("/init")
    public ResponseEntity<ConversionInitResponse> init(
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @Valid @RequestBody ConversionInitRequest req) {

        String cid = correlationId != null ? correlationId : CorrelationIdHolder.generate();
        CorrelationIdHolder.set(cid);

        ConversionSession session = conversionInitService.initiate(req, cid);

        return ResponseEntity.ok(ConversionInitResponse.builder()
                .conversionSessionId(session.getSessionId())
                .userType(session.getUserType())
                .expiresAt(session.getExpiresAt())
                .build());
    }
}
```

```java
// ido/src/main/java/kr/go/smes/ido/conversion/ConversionInitService.java

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversionInitService {

    private final AgencyMetaRepository agencyMetaRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final CallbackUrlValidator callbackUrlValidator;

    private static final Duration SESSION_TTL = Duration.ofMinutes(30);
    private static final Duration JWT_MAX_AGE  = Duration.ofMinutes(5);
    private static final String   SESSION_KEY_PREFIX = "conversion:session:";

    public ConversionSession initiate(ConversionInitRequest req, String cid) {

        // 1. 기관 메타 조회 (API Key Hash 포함)
        AgencyMeta agency = agencyMetaRepository.findActiveByCode(req.getAgencyCode())
                .orElseThrow(() -> {
                    log.warn("[ConversionInit] 기관 없음: agencyCode={} cid={}", req.getAgencyCode(), cid);
                    return new PlatformException(PlatformErrorCode.AGENCY_NOT_FOUND, cid);
                });

        // 2. JWT 서명 검증 (HMAC-SHA256, 기관 API Key)
        //    ※ API Key 원문은 K8s Secret에서 조회 (AgencyCredentialStore 위임)
        JwtPayload payload = verifySignedRequest(req.getSignedRequest(), agency, cid);

        // 3. 만료 검증 (발급 후 5분 이내)
        Instant issuedAt = Instant.ofEpochSecond(payload.getIat());
        if (issuedAt.plus(JWT_MAX_AGE).isBefore(Instant.now())) {
            log.warn("[ConversionInit] signed_request 만료: agencyCode={} iat={} cid={}",
                    req.getAgencyCode(), payload.getIat(), cid);
            throw new PlatformException(PlatformErrorCode.CONVERSION_REQUEST_EXPIRED, cid);
        }

        // 4. redirectUri — agency_meta.callback_whitelist 검증
        callbackUrlValidator.validate(
                payload.getRedirectUri(),
                agency.getCallbackWhitelist(),
                cid);

        // 5. ConversionSession 생성 → Redis 저장
        String sessionId = UUID.randomUUID().toString();
        ConversionSession session = ConversionSession.builder()
                .sessionId(sessionId)
                .agencyCode(req.getAgencyCode())
                .mbrId(payload.getMbrId())
                .redirectUri(payload.getRedirectUri())
                .returnClient(payload.getReturnClient())
                .userType(payload.getUserType())
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plus(SESSION_TTL))
                .build();

        redisTemplate.opsForValue().set(
                SESSION_KEY_PREFIX + sessionId,
                session,
                SESSION_TTL);

        log.info("[ConversionInit] 세션 생성: sessionId={} agencyCode={} cid={}",
                sessionId, req.getAgencyCode(), cid);

        return session;
    }

    private JwtPayload verifySignedRequest(String signedRequest, AgencyMeta agency, String cid) {
        try {
            // HMAC-SHA256 검증 — 기관 API Key 원문 조회
            // (AgencyCredentialStore.findSecret(agency.getAuthCredentialRef()))
            String apiKey = credentialStore.findSecret(agency.getAuthCredentialRef());
            Algorithm algorithm = Algorithm.HMAC256(apiKey);
            JWTVerifier verifier = JWT.require(algorithm)
                    .withClaim("sub", agency.getAgencyCode())
                    .build();
            DecodedJWT decoded = verifier.verify(signedRequest);

            return JwtPayload.builder()
                    .mbrId(decoded.getClaim("mbrId").asString())
                    .redirectUri(decoded.getClaim("redirectUri").asString())
                    .returnClient(decoded.getClaim("returnClient").asString())
                    .userType(decoded.getClaim("userType").asString())
                    .iat(decoded.getClaim("iat").asLong())
                    .build();
        } catch (JWTVerificationException e) {
            log.warn("[ConversionInit] JWT 서명 검증 실패: agencyCode={} error={} cid={}",
                    agency.getAgencyCode(), e.getMessage(), cid);
            throw new PlatformException(PlatformErrorCode.CONVERSION_SIGNATURE_INVALID, cid);
        }
    }
}
```

---

## 4. 구현 — FE: Step1.tsx 수정

```typescript
// Step1.tsx — signed_request 방식으로 전환

useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const signedRequest = params.get('signed_request');
    const agencyCode    = params.get('agency_code');

    // ── 레거시 평문 파라미터 지원 (이전 버전 기관 호환, 경고 로깅)
    const legacyRedirectUri  = params.get('redirect_uri');
    const legacyMbrId        = params.get('mbrId');
    const legacyReturnClient = params.get('return_client');
    const rawUserType        = params.get('userType');

    if (signedRequest && agencyCode) {
        // ── 신규: signed_request JWT 방식
        initConversion({ signedRequest, agencyCode })
            .then((res) => {
                if (res.statusCode === 200 && res.payload) {
                    updateData({
                        conversionSessionId: res.payload.conversionSessionId,
                        userType: res.payload.userType,
                    });
                    // userType에 따라 회원유형 사전 설정
                    if (res.payload.userType === 'ENT') {
                        setSelected('business');
                        updateData({ memberType: 'business' });
                    } else if (res.payload.userType === 'IND') {
                        setSelected('member');
                        updateData({ memberType: 'member' });
                    }
                } else {
                    setMissingParams(true);
                }
            })
            .catch(() => setMissingParams(true));

    } else if (legacyRedirectUri && legacyMbrId && legacyReturnClient) {
        // ── 레거시: 평문 파라미터 (호환 기간 운영 후 제거 예정)
        console.warn('[Conversion] 레거시 평문 파라미터 사용 — 기관 연동 담당자에게 signed_request 전환 요청');
        updateData({
            redirectUri: legacyRedirectUri,
            mbrId: legacyMbrId,
            initialClientId: legacyReturnClient,
        });
        // userType 설정 (기존 로직 유지)
        const ut = rawUserType === 'ENT' || rawUserType === 'IND' ? rawUserType : null;
        if (ut === 'ENT') { setSelected('business'); updateData({ memberType: 'business' }); }
        else if (ut === 'IND') { setSelected('member'); updateData({ memberType: 'member' }); }

    } else {
        setMissingParams(true);
    }
}, [updateData]);
```

---

## 5. 구현 — FE: Step8.tsx 버그 수정 (즉시 적용)

> **B-1 버그 수정**: `isSafeRedirectUri()` `*.smes.go.kr` 하드코딩 제거

### 수정 전 (버그)
```typescript
function isSafeRedirectUri(uri: string): boolean {
    const url = new URL(uri);
    return url.protocol === 'https:' && (
        url.hostname.endsWith('.smes.go.kr') ||  // ← 하드코딩 버그
        url.hostname === 'smes.go.kr'
    );
}
```

### 수정 후 — Option A: BE API 위임 (권장, signed_request 도입 시)

```typescript
// signed_request 방식 도입 후 — conversionSessionId 기반 redirectUri 서버에서 조회
// Step8에서 직접 검증 불필요: BE ConversionInitService가 이미 검증한 redirectUri 사용

const handleLogin = async (): Promise<void> => {
    if (!data.conversionSessionId) {
        window.location.href = ROUTES.LOGIN;
        return;
    }
    // BE에서 검증된 redirectUri 조회 (ConversionSession에 저장된 값)
    const res = await getConversionSession(data.conversionSessionId);
    if (res.statusCode === 200 && res.payload?.redirectUri) {
        window.location.href = res.payload.redirectUri;
    } else {
        window.location.href = ROUTES.LOGIN;
    }
};
```

### 수정 후 — Option B: 환경변수 기반 (즉시 적용 가능, 레거시 호환)

```typescript
// .env.production
// REACT_APP_REDIRECT_ALLOWED_ORIGINS=https://www.bizinfo.go.kr,https://www.sbiz.or.kr,...

function isSafeRedirectUri(uri: string): boolean {
    try {
        const url = new URL(uri);
        if (url.protocol !== 'https:') return false;

        // 환경변수 기반 허용 도메인 목록
        const allowedOrigins = (
            process.env.REACT_APP_REDIRECT_ALLOWED_ORIGINS ?? ''
        ).split(',').map(o => o.trim()).filter(Boolean);

        // 개발환경 fallback
        if (allowedOrigins.length === 0) {
            // ⚠️ 운영 환경에서는 반드시 환경변수 설정 필요
            console.error('[Security] REACT_APP_REDIRECT_ALLOWED_ORIGINS 미설정 — 모든 redirect 차단');
            return false;
        }

        return allowedOrigins.some(allowed => {
            try {
                const allowedUrl = new URL(allowed);
                // 와일드카드 도메인 지원 (*.domain.com)
                if (allowedUrl.hostname.startsWith('*.')) {
                    const suffix = allowedUrl.hostname.slice(1); // .domain.com
                    return url.hostname.endsWith(suffix) || url.hostname === suffix.slice(1);
                }
                // 정확한 origin 일치
                return url.origin === allowedUrl.origin;
            } catch {
                return false;
            }
        });
    } catch {
        return false;
    }
}
```

> **권장**: 즉시는 Option B 적용 → signed_request 도입 완료 후 Option A로 전환.

---

## 6. BE: application.yml 수정

```yaml
# application.yml

ido:
  fe:
    session:
      sliding-ttl-minutes: 30
      absolute-timeout-minutes: 480

    # returnUrl 화이트리스트 — 실제 68개 기관 URL 등록 필요 (현재 PoC 더미)
    # 운영 배포 시 환경변수 또는 외부 설정 서버(Config Server)로 관리
    allowed-return-urls:
      # ── 중소벤처기업부 직속 서비스 ──────────────────────────────
      - https://www.smes.go.kr
      - https://www.bizinfo.go.kr
      - https://www.mss.go.kr
      # ── 산하기관 ─────────────────────────────────────────────
      - https://www.sbiz.or.kr
      - https://www.fanfan.or.kr
      - https://www.kosmes.or.kr
      # ... 나머지 기관 URL 추가 필요 (총 68개)
      # ── 개발/스테이징 ─────────────────────────────────────────
      - ${AGENCY_STUB_URL:http://localhost:8084}
      - ${REACT_DEV_URL:http://localhost:3000}

  # 전환 세션 (ConversionInitService)
  conversion:
    session-ttl-minutes: 30          # ConversionSession Redis TTL
    signed-request-max-age-minutes: 5 # JWT exp 허용 시간
```

### 운영 환경 권장: ConfigMap / Secret

```yaml
# k8s/configmap-ido.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: ido-config
data:
  IDO_FE_ALLOWED_RETURN_URLS: |
    https://www.bizinfo.go.kr,
    https://www.sbiz.or.kr,
    https://www.kosmes.or.kr,
    ...
```

---

## 7. 기관별 `agency_meta.callback_whitelist` DB 등록 (Layer 3)

Layer 3(`CallbackUrlValidator`)는 구조적으로 올바릅니다.  
Flyway 마이그레이션 또는 관리자 API를 통해 기관별 URL을 등록합니다.

```sql
-- 기관별 callback_whitelist 등록 예시
UPDATE ido.agency_meta
SET callback_whitelist = '["https://www.bizinfo.go.kr/callback", "https://www.bizinfo.go.kr/mypage/onepass"]'
WHERE agency_code = 'BIZINFO_001';

UPDATE ido.agency_meta
SET callback_whitelist = '["https://www.sbiz.or.kr/auth/onepass-callback"]'
WHERE agency_code = 'SBIZ_001';
```

### 와일드카드 패턴 사용 (서브도메인 허용 시)

```sql
UPDATE ido.agency_meta
SET callback_whitelist = '["*.bizinfo.go.kr"]'
WHERE agency_code = 'BIZINFO_001';
-- → https://www.bizinfo.go.kr, https://api.bizinfo.go.kr 등 모두 허용
```

---

## 8. 단계별 적용 로드맵

| 단계 | 작업 | 우선순위 | 담당 |
|---|---|---|---|
| **즉시** | Step8.tsx `isSafeRedirectUri()` Option B 수정 | 🔴 긴급 (B-1 버그) | FE팀 |
| **즉시** | `application.yml` `allowed-return-urls` 실제 기관 URL 등록 | 🔴 긴급 (B-2 미설정) | 운영팀 |
| **즉시** | `agency_meta.callback_whitelist` DB 기관별 등록 | 🔴 긴급 | 운영팀 |
| **단기** | `ConversionInitController` + `ConversionInitService` 구현 | 🟠 높음 | BE팀 |
| **단기** | `Step1.tsx` signed_request 방식 전환 | 🟠 높음 | FE팀 |
| **중기** | 레거시 평문 파라미터 방식 제거 | 🟡 중간 | FE/BE팀 |
| **중기** | Step8.tsx Option A (BE API 위임) 전환 | 🟡 중간 | FE팀 |

---

> **이전 문서**: [GUIDE-001: URL 플로우 분석](./01-agency-conversion-url-flow.md)  
> **다음 문서**: [GUIDE-003: 기관 오픈 시 URL 샘플](./03-conversion-launch-sample.md)
