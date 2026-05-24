# OnePass 전체 서비스 GO/NO-GO 재판정 (실제 흐름 우선)

- 작성일시: 2026-05-24 14:00 (KST)
- 기준 브랜치: `main`
- 기준 커밋: `388b622 fix(test): 출시 NO-GO 5+1건 일괄 해소 — IdO/Q-IM/Agency E2E/Agent/FE (#193)`
- 평가 기준: 테스트케이스 개수보다 **실제 SSO/IM 흐름 완전성**(기관 진입, 인증, 검증, handoff, 세션/후속 처리)을 우선

## 최종 판정

**NO-GO**

핵심 이유는 다음 두 가지다.

1. `q-im` 통합 테스트에서 12건 실패가 재현되어 회원 생명주기/동의/기업회원 전환/outbox 멱등성의 안정성이 아직 불충분하다.
2. 런타임 smoke에서 SSO/IM 앱 엔드포인트가 살아있지 않아(`8081/8082/8083/8084` 거부) 실제 연동 플로우 완전성을 운영 형태로 증명하지 못했다.

## 실행 과정 및 결과

### 1) 핵심 모듈 테스트 (현재 main 재실행)

| 영역 | 명령 | 결과 | 비고 |
| --- | --- | --- | --- |
| Agency E2E (핵심 흐름) | `.\gradlew.bat :agency-stub:test --tests "*AgencyHandoffE2EIntegrationTest*" --no-daemon --max-workers=1` | PASS | 실제 테스트 재실행 확인(UP-TO-DATE 아님) |
| IdO | `.\gradlew.bat :ido:test --no-daemon --max-workers=1` | PASS | 실제 테스트 재실행 확인 |
| Q-Sign | `.\gradlew.bat :q-sign:test --no-daemon --max-workers=1` | PASS | 실제 테스트 재실행 확인 |
| Q-IM | `.\gradlew.bat :q-im:test --no-daemon --max-workers=1` | **FAIL** | `251 tests completed, 12 failed` |

참고: Windows 파일 잠금 이슈(`Unable to delete ... test-results/test/binary`, `...build/classes...`)가 간헐적으로 발생해 잠금 디렉터리 정리 후 재실행했다.

### 2) Q-IM 실패 상세 (출시 차단)

대표 실패군:

- `QimLifecycleIntegrationTest`
  - `ObjectOptimisticLockingFailureException`
  - `StaleObjectStateException`
  - 탈퇴 상태 전이/보호자 동의/기업회원 전환 관련 assertion 실패
- `OutboxIntegrationTest`
  - 중복 `eventId` 멱등 처리 기대 불일치(예외 미발생 케이스)

의미:

- 회원 상태 전이의 동시성/정합성 보장이 아직 불안정하다.
- Outbox 멱등성은 운영 장애 시 재처리 안정성과 직결되므로 GO 조건에 미달한다.

### 3) 실제 런타임 스모크 (흐름 우선 검증)

실행:

- `pwsh -File infra/scripts/smoke-test.ps1 -SkipMonitoring -TimeoutSec 8`

결과:

- PASS=1 / FAIL=6 / SKIP=1
- 실패 항목:
  - Agency-Stub `actuator/health`
  - Q-Sign/Q-IM/IdO `actuator/prometheus`
  - Q-IM 내부 API 보안 체크
  - Frontend(Nginx) 접근

당시 컨테이너 상태:

- 인프라(DB/Redis/Kafka 등)는 기동
- SSO/IM 앱 컨테이너는 미기동 상태 (포트 거부)

추가 확인:

- `docker compose ... up -d` 시 앱 이미지 pull 실패(`onepass-qim`, `onepass-agency-stub` 등 레지스트리 미존재)
- 결과적으로 운영 형태 런타임 플로우를 끝까지 재현하지 못함

## 흐름 중심 판정 해석

좋아진 점:

- `AgencyHandoffE2EIntegrationTest`는 PASS로 유지되어 기관 handoff 시나리오 단위에서는 개선됨
- `IdO`, `Q-Sign` 테스트군은 현재 기준 PASS

GO를 막는 점:

1. Q-IM 통합 실패 12건이 남아 회원 라이프사이클/멱등성 핵심 축이 불안정
2. 앱 런타임이 실제로 올라온 상태에서 전체 플로우(SSO→IM→IdO→Agency→FE) 성공 증거가 부족

따라서 현재는 **NO-GO**가 타당하다.

## 출시 전 필수 조치 (우선순위)

1. `q-im` 12건 실패 원인 수정 및 재검증
2. 앱 컨테이너 빌드/기동 경로 확정
   - 사전 빌드 이미지 정책 또는 compose에 빌드 경로 명시 일관화
3. 런타임 smoke 재실행 시 최소 아래를 PASS로 확보
   - `8081/8082/8083/8084` 헬스 및 핵심 API
   - 내부 인증(잘못된 키 차단/정상 키 허용)
   - agency handoff 왕복 시나리오
4. 위 1~3 완료 후 GO/NO-GO 재판정

## 결론

`main` 최신 기준에서, 테스트 커버리지 양 자체보다 중요한 실제 흐름 완전성 관점으로 보더라도 현재 상태는 **NO-GO**다.  
핵심 차단점은 `q-im` 통합 안정성(12 fail)과 런타임 연동 플로우 실증 부족이다.

