# ADR-012: React FE 이중 인스턴스 (beInstance/extInstance) 채택

| 항목 | 내용 |
|------|------|
| **ID** | ADR-012 |
| **제목** | FE에서 beInstance(IdO 경유)와 extInstance(Q-IM 직접) 이중 Axios 인스턴스 분리 |
| **상태** | ✅ Accepted |
| **결정일** | 2026 (Sprint 15) |
| **결정자** | FE 아키텍처 위원회 |
| **관련 파일** | `idem-console/frontend/src/api/beInstance.ts`, `idem-console/frontend/src/api/extInstance.ts`, `idem-hub/ext/ExtProxyController.java` |

---

## 컨텍스트 (Context)

초기 FE 설계에서 `extInstance`가 Q-IM(8082)에 직접 호출하던 방식의 보안 문제:

1. **CI 직접 전송 (Q3=B 위반)**: FE → Q-IM에 CI 원문 직접 전송 → CI 중간 탈취 가능
2. **API Key FE 번들 노출**: Q-IM External API Key가 webpack 번들에 포함 → 브라우저 개발자 도구로 노출
3. **CORS 의존**: Q-IM CORS 설정으로 허용 → 보안 경계 약화
4. **운영 환경 직접 노출**: Q-IM 포트(8082) 외부 오픈 필요

---

## 결정 (Decision)

**FE Axios 인스턴스를 2개로 분리**하고, 보안이 필요한 모든 호출은 `beInstance(IdO :8083)` 경유로 전환한다.

### 인스턴스 분리 설계

```
beInstance → IdO(:8083) → Q-IM(:8082) [보안 경유]
              서버사이드 API Key 주입
              CI 처리 서버사이드

extInstance → IdO(:8083)/api/ext/** → Q-IM(:8082) [Forward Proxy]
              (레거시 호환 — 점진적 beInstance 전환 중)
```

### beInstance 사용 규칙

```typescript
// ✅ beInstance — 보안이 필요한 호출
import { beApiInstance } from 'api/beInstance';

// CI Token 교환 (CI 원문은 ido에서만 처리)
const tokenResponse = await beApiInstance.post('/api/v1/auth/ci-token', {
    encryptedCi,  // AES-GCM 암호화된 CI만 전송
    flowContext: 'PROVISION_USER'
});

// NICE CI Check
const ciCheckResult = await beApiInstance.post('/api/v1/auth/nice/ci-check', params);
```

### extInstance 사용 규칙 (점진적 폐기)

```typescript
// ⚠️ extInstance — 레거시 호환 (Q-IM 공개 External API만 허용)
// ExtProxyController가 /api/ext/ci/** 는 403으로 차단
import extInstance from 'api/extInstance';

// 회원 등록 (Q-IM ext API — 공개 가능)
const result = await extInstance.post('/api/ext/register/individual', props);
```

### CI 보안 패치 (Q3=B)

```
변경 전 (Q3=B 위반):
  FE → extInstance → Q-IM /api/ext/ci/token (CI 직접 전송)

변경 후 (Q3=B 준수):
  FE → AES-GCM 암호화(CI) → beApiInstance → IdO /api/v1/auth/ci-token
    IdO 내부: 복호화 → CI 처리 → ciToken(JWT) 반환
    CI 원문은 IdO BE에서만 존재 (FE/FE 로그에 미노출)
```

### ExtProxyController (IdO) — Forward Proxy

```java
// ExtProxyController.java
// 역할: extInstance 요청 → Q-IM forward + 서버사이드 API Key 주입
// 보안: /api/ext/ci/** 경로 403 차단 (Q3=B)
@RequestMapping("/api/ext")
public class ExtProxyController {
    @RequestMapping("/ci/**")
    public ResponseEntity<String> blockCiDirectAccess() {
        return ResponseEntity.status(403)
            .body("{\"error\":\"CI_DIRECT_ACCESS_BLOCKED\"}");
    }

    @PostMapping("/**")
    public ResponseEntity<byte[]> proxyPost(HttpServletRequest request) {
        // X-Ext-Api-Key 서버사이드 주입 (FE 번들 미노출)
    }
}
```

### 마이그레이션 현황

| API 호출 | 이전 | 이후 | 상태 |
|---------|------|------|------|
| CI Token 교환 | extInstance → Q-IM | beInstance → IdO | ✅ 완료 |
| 회원 신규 등록 | extInstance → Q-IM | extInstance → IdO(Proxy) | ✅ 완료 |
| 회원 정보 조회 | extInstance → Q-IM | extInstance → IdO(Proxy) | ✅ 완료 |
| CI Check | - | beInstance → IdO | 🔲 미연결 (TODO) |

---

## 결과 (Consequences)

### 긍정적 효과
- **CI 원문 보호**: FE에서 CI 직접 Q-IM 전송 차단
- **API Key 은닉**: Q-IM API Key가 FE 번들에 미포함
- **Q-IM 포트 폐쇄**: 8082 외부 직접 노출 불필요 (IdO 경유)
- **점진적 전환**: extInstance → beInstance 단계별 마이그레이션 가능

### 부정적 효과 / 주의사항
- **IdO 경유 지연**: beInstance 호출 시 ~1ms 추가 (허용 범위)
- **ExtProxyController 단일 장애점**: IdO 장애 시 extInstance 호출도 중단
- **레거시 extInstance 유지**: 마이그레이션 완료 전까지 2중 관리

### 포기한 대안
- **BFF(Backend for Frontend) 별도 서비스**: 추가 인프라 비용, 현재 IdO가 BFF 역할 수행
- **FE 직접 CORS 허용 유지**: 보안 요구사항(Q3=B) 위반

---

## 관련 ADR

- [ADR-001](ADR-001-ido-microservice-architecture.md) — IdO 오케스트레이터 (ExtProxy 역할)
- [ADR-011](ADR-011-hmac-sha256-gateway-auth.md) — HMAC (기관 Gateway 인증과 구분)
