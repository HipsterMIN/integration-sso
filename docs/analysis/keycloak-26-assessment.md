# Keycloak 26 올림 영향 평가 (2026-10-15, 1.1.3 기준 — 추록 §8: 26.8.2 대응·최종 계약 리허설, 2026-11-12)

취약점 점검 1회차의 서드파티 이미지 결과(`docs/certification/vulnerability-review-2026-10.md` §4.4·§6)가 남긴 숙제다. 설치본이 쓰는 `quay.io/keycloak/keycloak:24.0`(= 24.0.5)은 OS 21 HIGH + Java jar 77(CRITICAL 6)을 안고 있고, 24.0 줄기의 수정판은 24.0.9 까지만 나왔으며 2025 년 이후 CVE(SAML·조직 초대·redirect_uri·authorization code 위조 등)는 **26.x 에서만** 고쳐졌다. 이 문서는 "언제·어떻게 26 으로 가는가" 를 2026-11 점검 회차에서 결정할 수 있게, **Keycloak 26.8.1 을 샌드박스에서 실제로 띄워**(24 스키마 복사본 위의 마이그레이션과 빈 스키마 새 설치, 둘 다) 설치본 계약·Idem 코드 영향·시험 결과·단계 계획·권고를 적는다.

## 0. 요약

| 항목 | 결과 |
|---|---|
| 대상 | Keycloak **26.8.1**(2026-10 현재 최신, Quarkus 3.40.1) ← 설치본 24.0(= 24.0.5). 중간 메이저 25 를 거치지 않아도 된다(26.8.1 이 24 스키마를 25.0.0 → 26.8.0 으로 직접 올린다) |
| 시험(샌드박스, 2026-10-15) | ① 24.0.5 스키마 복사본 위 기동 — 모델 마이그레이션 자동(25.0.0·26.0.0·…·26.8.0, 13.7 초), realm import 는 "이미 있음 → 건너뜀", `KEYCLOAK_ADMIN` 으로 만든 관리자 유효 ② 빈 스키마 + 저장소의 `realm-export.json`(24 시절) import — 28.7 초, `${VAR}` secret 치환·IdP 4·시험 사용자 1 ③ 전체 스택(registry·authz·gate·hub) + **설치본 스모크 ①~⑧ 통과** ④ 표준 OIDC(OIDC_RP) k6 시나리오 12 — 드라이런 통과, 부하 §5.4 ⑤ 브로커(Keycloak 중계) 로그인 끝-끝 통과 — 단, **Idem 쪽 결함 5건을 고친 뒤**(§5.5, Keycloak 버전과 무관) |
| 설치본 계약 변경(필수) | hostname v1·`KC_PROXY` 가 26.0 에서 **제거** → `KC_HOSTNAME_URL`/`KC_HOSTNAME_ADMIN_URL`/`KC_HOSTNAME_STRICT_HTTPS`/`KC_PROXY=edge` 를 `KC_HOSTNAME`/`KC_HOSTNAME_ADMIN`/`KC_PROXY_HEADERS=xforwarded` 로(compose·CI 스모크·개발 compose 2종; Helm 은 proxy-headers 만 이미 되어 있다) · `KEYCLOAK_ADMIN(_PASSWORD)` → `KC_BOOTSTRAP_ADMIN_USERNAME/PASSWORD`(deprecated, 아직 동작) · compose 의 `start-dev` 는 26.6 부터 **localhost 에만 바인드** → `KC_HTTP_HOST=0.0.0.0`(또는 `start`) · Helm 프로브 `/health/*` 는 **관리 포트 9000** 으로(앱 포트에서는 404) · 업그레이드 창에 **활성 세션 전부 유실**(Infinispan 마샬링 변경, 재로그인) |
| Idem 코드 영향 | **변경 없음** — gate 프록시(X-Forwarded-Host 에 포트·X-Forwarded-Port 둘 다 보낸다, 26.2)·discovery issuer·`idem-provisioner` 관리 API(client CRUD·secret, manage-clients 직접 부여 — 26.7.1/26.8)·`idem-session-manager` 세션 종료·JWKS·`client_secret_post`·PKCE S256 모두 그대로 동작. 26.5 의 `sid`(UUID → base64 24자)는 `idp_sid VARCHAR(128)`·Redis 세션에 들어간다 |
| 시험이 드러낸 것(덤) | Keycloak 중계 로그인 경로(`/api/v1/broker/{provider}/authorize` → gate → hub 완료 콜백)가 **1.0.0 부터 한 번도 끝까지 가지 못했다** — hub 캐시 SpEL 오류(항상 500)·hub 완료 콜백 화이트리스트·gate 주체 키 미전달·gate `jsonb` 바인딩(auth_result·outbox)·gate `idp_provider` 행 누락. 설치본 스모크·k6·IT 가 MOCK(SPI)·OIDC_RP 만 밟아 잡지 못했다. PR-G2-10 에서 수정 + IT 2종(hub `BrokerLoginIntegrationTest`, gate `AuthResultJpaPostgresIntegrationTest`) |
| 공수(추정) | 설치본 계약(compose·Helm·CI·개발 compose·문서) 1일 + 리허설(설치본 스모크·K8s 리허설·오프라인 번들·캡처) 1일 + 업그레이드 절차·매뉴얼 0.5일 — AI 기준, 사용자 리허설 1회. Spring Boot 4 이행과 **독립**(코드 변경 없음) |
| 권고 | **1.2 에서 올린다**(환경변수 계약이 바뀌므로 플랜 §4 규칙상 1.1.x 패치가 아니다). GS 시험 일정과 맞물리면 Spring Boot 4 보다 먼저 — 작고 독립적이며 취약점 수가 가장 많은 이미지다. 그 전 1.1.x 설치본은 24.0.5 그대로(24.0 라인에 더 나올 수정판 없음). 결정은 2026-11 회차 |

## 1. 왜 지금 보는가

- `third-party-scan`(main push) 첫 결과(run 38665901991): `keycloak:24.0` 에 OS 21 HIGH + jar 77(HIGH 71·CRITICAL 6) — `keycloak-services` 16, `keycloak-saml-core` 2, `postgresql 42.7.2`, `bcprov 1.77` 등. 24.0 라인은 24.0.9 가 마지막이고 그 뒤 CVE 는 26.x 에서만 고쳐졌다. 이미지 자체의 결함은 Keycloak 을 gate 뒤에 숨겨도 남는다.
- Idem 은 Keycloak 을 **숨긴 채** 쓴다(ADR-014): 브라우저·기관 RP 는 gate 의 `/realms/**` 프록시만 보고, 관리 콘솔은 로컬 포트, 관리 API 는 `idem-provisioner`·`idem-session-manager` 서비스 계정 둘뿐이다. 접점이 좁아 올림의 영향 범위도 좁다 — 그것을 실제로 확인하는 것이 이 평가다.
- 24 → 26 은 메이저 둘을 건넌다. 설치본의 Keycloak 환경변수 계약(`docs/install-inputs.md`)이 바뀌므로 패치 릴리스로는 못 한다(플랜 §4).

## 2. 대상 버전과 변경 요지 (24.0 → 26.8.1)

Keycloak 업그레이드 가이드(26.0 ~ 26.8.1)에서 Idem 에 닿는 항목만. ✓ = 이미 맞다, △ = 설치본·문서 변경, ✗ = 영향 없음.

| 버전 | 변경 | Idem 영향 |
|---|---|---|
| 26.0 | **hostname v1 제거**(`hostname-url`·`hostname-admin-url`·`hostname-strict-https`…, 25 에서 deprecated) | △ compose·CI 스모크·개발 compose 가 `KC_HOSTNAME_URL`·`KC_HOSTNAME_ADMIN_URL`·`KC_HOSTNAME_STRICT_HTTPS` 를 쓴다 → `KC_HOSTNAME`·`KC_HOSTNAME_ADMIN`(+`KC_HOSTNAME_STRICT=false`). Helm 도 `KC_HOSTNAME_URL`·`KC_HOSTNAME_ADMIN_URL` |
| 26.0 | **`proxy` 옵션 제거**(24 에서 deprecated) → `proxy-headers` | △ compose·CI·개발 compose 의 `KC_PROXY=edge` → `KC_PROXY_HEADERS=xforwarded`(Helm 은 1.1 에서 이미 바꿈) |
| 26.0 | `KEYCLOAK_ADMIN(_PASSWORD)` deprecated → `KC_BOOTSTRAP_ADMIN_USERNAME/PASSWORD`(임시 부트스트랩 관리자) | △ 아직 동작하지만 바꾼다. 기존 설치본의 관리자 계정은 DB 에 있으므로 그대로 유효(§5.1 확인) |
| 26.0 | Infinispan 마샬링 변경 — **업그레이드 때 모든 캐시가 비워진다**(세션 유실). 25 를 거치며 persistent sessions 를 켜면 보존 | △ 단일 인스턴스·보수 창 업그레이드 → 활성 Keycloak 세션(= 기관 RP 의 SSO 세션)은 재로그인. hub FE 세션(Redis)·Handoff 티켓은 영향 없다. 25 경유는 값어치가 없다(우리 세션은 짧다) |
| 26.0 | 모든 사용자 세션을 DB 에 저장(persistent sessions 기본) | △ PG 쓰기 증가(로그인·로그아웃마다). `idem` DB 의 `keycloak` 스키마 — 백업 대상에 이미 포함 |
| 26.0 | `--import-realm` 이 master realm 도 import 가능, `kc.sh import` 에도 자리표시자 치환 | ✓ 설치본은 시작 시 import(`IGNORE_EXISTING`) — 의미 그대로 |
| 26.2 | `X-Forwarded-Host` 에 포트가 없으면 포트를 붙이지 않는다 | ✓ gate `KeycloakProxy` 가 `X-Forwarded-Host` 에 포트를 넣고 `X-Forwarded-Port` 도 보낸다 — issuer·로그인 폼 action 이 gate 공개 URL 로 나오는 것을 확인(§5.3) |
| 26.2 | JWT client 인증(`private_key_jwt`) 규격 정렬 | ✗ Idem client 는 전부 `client_secret_post` |
| 26.5 | **`session_state`·`sid` 가 UUID 가 아니다**(base64 랜덤 문자열) | ✓ gate·hub 는 문자열로만 다룬다(`idp_sid VARCHAR(128)`, Redis FE 세션, `DELETE /admin/realms/{realm}/sessions/{sid}`). `UUID.fromString` 없음 |
| 26.6 | **dev 모드(`start-dev`)는 `http-host` 기본이 localhost** | △ compose 는 `start-dev` + `127.0.0.1:8088:8080` 포트 매핑 — 컨테이너 안 8080 이 loopback 에만 열리면 gate·hub(다른 컨테이너)가 못 붙는다 → `KC_HTTP_HOST=0.0.0.0`(§5.2 확인) 또는 `start`(Helm 처럼). CI 스모크는 `--network host` 라 localhost 바인드로도 앱(러너 프로세스)이 닿지만 compose 와 같은 값으로 맞춘다 |
| 26.6 | 초기화 중에도 포트를 연다(health 켜진 경우) — 프록시는 `/health/ready` 로 | △ compose 헬스체크는 `/realms/idem` 200 — import 전에는 404 라 그대로 쓸 수 있다. Helm `readinessProbe` 도 `/realms/idem` |
| 26.6 | `secure-client-uris` 정책이 post-logout·logo·policy URL 에 https 강제 | ✗ 그 client policy 를 켜지 않았다. 기관 OIDC_RP client 의 redirect URI 는 프로파일 값 그대로 |
| 26.7 | 생성 client secret 이 **86자**(종전 32자) | ✓ Idem 은 secret 을 저장하지 않고 발급 응답에 한 번 돌려준다(`OidcRpClientProvisioner`). 기관 쪽 저장 길이만 안내(SDK·가이드에 길이 가정 없음) |
| 26.7 | 세션 쿠키 해시 SHA-384 | ✗ gate 는 쿠키를 그대로 중계한다 |
| 26.7.1 | **관리 API 는 직접 부여된 admin 역할만** 인정(프로토콜 매퍼·그룹 상속 불인정) | ✓ `idem-provisioner`(manage-clients)·`idem-session-manager`(view-users·manage-users)는 서비스 계정에 realm-management 역할을 직접 부여(realm-export) — 26.8.1 에서 토큰·관리 API 호출 확인(§5.1) |
| 26.8 | view-clients 만 있으면 client secret 이 가려진다 | ✓ provisioner 는 manage-clients |
| 26.8 | `client-secret-rotation` 기능 기본 활성 | ✓ 회전 정책(client policy)을 두지 않으면 종전과 같다 — provisioner 의 `POST /client-secret` 회전은 즉시 교체 |
| 26.8 | 로그인 실패(brute force) 저장소가 DB(`login-failures:v2`) | △ realm 은 `bruteForceProtected=true` — PG 쓰기 증가. 동작 변화 없음 |
| 26.8 | 'Full scope allowed' 스위치 deprecated(향후 제거) | △ realm-export 의 client 4개(`idem-gate`·`idem-hub`·`idem-provisioner`·`idem-session-manager`)가 `fullScopeAllowed=true` — 토큰 발급마다 WARN. 기관 OIDC_RP client 는 provisioner 가 false 로 만든다. 26 올림 때 realm-export 의 내부 client 4개도 false + 필요한 scope 매핑으로 |
| 26.8 | `initiating_idp` 로그아웃 파라미터 무시, 비활성 client 는 aud 에서 제외, redirect URI 호스트 대소문자 무시 비교 | ✗ |
| 26.8.1 | SMTP StartTLS 강제, X.509 인증기 CRL 경로 제한 | ✗ (SMTP·X.509 미사용) |
| 전 버전 | 기본 활성 deprecated 기능 경고 — `identity-brokering-api:v1`, `twitter-broker:v1`, sticky route `shouldAttachRoute` | ✗ 경고만. IdP 브로커 API v2 는 realm 설정 변경 없이 쓸 수 있다(26.x 가이드) |

## 3. 설치본·문서 계약 영향 (고칠 곳)

| 어디 | 지금(24) | 26 |
|---|---|---|
| `infra/docker/compose.install.yml` keycloak | `image: quay.io/keycloak/keycloak:24.0`, `start-dev --http-port=8080 --import-realm`, `KEYCLOAK_ADMIN(_PASSWORD)`, `KC_HOSTNAME_STRICT_HTTPS`, `KC_PROXY: edge`, `KC_HOSTNAME_URL`, `KC_HOSTNAME_ADMIN_URL` | `keycloak:26.8`, `KC_BOOTSTRAP_ADMIN_USERNAME/PASSWORD`, `KC_HOSTNAME`/`KC_HOSTNAME_ADMIN`, `KC_PROXY_HEADERS=xforwarded`, **`KC_HTTP_HOST=0.0.0.0`**, `KC_HOSTNAME_STRICT_HTTPS` 삭제. 헬스체크(`/realms/idem` 200)는 그대로 |
| `infra/helm/idem/templates/deployment-keycloak.yaml`·`values.yaml` | `start`(production), `KC_HOSTNAME_URL`·`KC_HOSTNAME_ADMIN_URL`·`KC_HOSTNAME_STRICT_HTTPS`·`KEYCLOAK_ADMIN`, 프로브 `/health/started`·`/health/live` **8080** | `tag: "26.8"`, hostname v2 환경변수, `KC_BOOTSTRAP_ADMIN_*`, 프로브는 관리 포트 **9000**(`containerPort` 추가, `KC_HTTP_MANAGEMENT_PORT` 기본 9000). `optimized` 이미지(`kc.sh build --db=postgres --health-enabled=true`)도 26 베이스로 |
| `.github/workflows/ci.yml` k6 스모크 잡(`docker run … keycloak:24.0 start-dev`) | compose 와 같은 24 환경변수 | compose 와 같이 바꾼다(`--network host` 라 바인드 주소는 문제가 안 되지만 환경변수 계약은 compose 와 같게). `third-party-scan` 은 compose 의 이미지 태그를 읽으므로 자동으로 26 을 긁는다(정정 §8.3: 매트릭스는 고정 문자열이다 — 지금은 `docs-lint` K 규칙이 compose 와 같은 태그임을 강제한다) |
| `infra/docker/docker-compose.yml`·`compose.sso-im.yml`(개발용) | 24 환경변수 | 같이 바꾼다(개발자 로컬) |
| `scripts/k8s/rehearsal/values-rehearsal.yaml`·`rehearsal.sh` 주석 | `KC_PROXY=edge` 언급 | 문구 |
| `docs/install.md`·`docs/install-inputs.md`·`docs/manuals/installation-manual.md`·`ADR-014`·gate/hub `application.yml` 주석 | `KC_HOSTNAME_URL` = gate 공개 URL 이라는 설명 | `KC_HOSTNAME` 으로 |
| `infra/docker/keycloak/realm-export.json` | 24 에서 export 한 realm(`fullScopeAllowed: true` ×4, client scope `openid` 참조) | 26.8.1 이 그대로 import 한다(§5.1). `openid` scope 참조 경고는 24 도 같다. 내부 client 의 `fullScopeAllowed` 를 false 로 바꾸는 것은 선택(26.8 deprecation) |
| 업그레이드 절차(`CHANGELOG` 업그레이드 메모·운영 가이드) | — | ① 보수 창 공지(Keycloak 세션 재로그인) ② `install.env` 변수명 교체(`KEYCLOAK_ADMIN_PASSWORD` 값은 그대로 `KC_BOOTSTRAP_ADMIN_PASSWORD` 로) ③ 이미지 태그 → 첫 기동에서 Liquibase·모델 마이그레이션 자동(되돌리기 불가 — **DB 백업 먼저**) ④ 스모크 ①~⑧ |

## 4. Idem 코드 쪽 계약 점검 (변경 없음)

| 접점 | 코드 | 26.8.1 에서 |
|---|---|---|
| 브라우저·RP → gate `/realms/**` 프록시 | gate `KeycloakProxy` — hop-by-hop·기존 `X-Forwarded-*` 제거 후 `X-Forwarded-Host`(포트 포함)·`Proto`·`Port`·`For` 설정, 내부 Location 재작성 | 로그인 화면 200, 폼 action = gate 공개 URL(`http://localhost:8081/realms/idem/login-actions/authenticate…`), 쿠키 `AUTH_SESSION_ID`·`KC_RESTART`(Secure·SameSite=None — 24 와 같다) |
| discovery·issuer | gate `OidcDiscovery`, hub `idem.hub.oidc-rp.issuer` | `issuer = http://localhost:8081/realms/idem`(= `KC_HOSTNAME`), X-Forwarded 헤더 유무와 무관 |
| 표준 OIDC code+PKCE 토큰 교환 → hub 판정 → userinfo 보강 | gate `OidcFrontController`, hub `OidcRpAccessService` | k6 12 드라이런 체크 7/7, 부하 §5.4 |
| 기관 client 프로비저닝 | hub `OidcRpClientProvisioner` → `KeycloakAdminClient`(`idem-provisioner` client_credentials, `/admin/realms/idem/clients…`, `/client-secret` GET/POST) | 토큰 200, client 생성·`fullScopeAllowed=false`·PKCE S256·secret 회전 OK(스모크 ③·`idem-svc-PERF_RP`) |
| SLO | gate `KeycloakLogoutService`(`idem-session-manager`: `DELETE /sessions/{sid}`, `POST /users/{sub}/logout`) | 토큰 200. `sid` 는 base64 24자 — 문자열 그대로 전달 |
| Keycloak 중계 로그인(1.0 브로커 경로, 1.1 로그인 프런트 `broker:<name>`) | hub `BrokerService`(q-sign 모드: gate `/api/v1/oidc/{provider}/auth-url`) → gate `KeycloakCallbackService`(code 교환·ID Token JWKS 검증·`auth_result`·outbox) → hub `/api/internal/v1/oidc/complete` → FE 세션·바인드·continue | 끝-끝 통과 — **Idem 결함 5건 수정 뒤**(§5.5). Keycloak 과의 계약(code 교환·JWKS·`sid`·`sub`)은 그대로였다 |
| 실시간 전파·세션 advisory | 26 와 무관(Idem 내부) | — |

## 5. 시험 기록 (2026-10-15, 샌드박스 — 4 vCPU 단일 호스트, PG 16·Redis 로컬, 26.8.1 zip 배포본 `start-dev`)

### 5.1 24 스키마 복사본 위 기동 (업그레이드 경로) 와 새 설치

- **업그레이드**: 24.0.5 가 쓰던 `keycloak` 스키마를 DB `idem_kc26` 로 복사 → 26.8.1 `start-dev --import-realm`(hostname v2·`KC_PROXY_HEADERS=xforwarded`·`KC_BOOTSTRAP_ADMIN_*`·`KC_HTTP_HOST=0.0.0.0`). 로그: `Migrating older model to 25.0.0 … 26.0.0 … 26.1.0 … 26.2.0 … 26.3.0 … 26.4.0 … 26.4.3 … 26.6.1 … 26.6.2 … 26.7.0 … 26.8.0`(realm master·idem 각각), `Realm 'idem' already exists. Import skipped`, `Bootstrap completed in 13.7 s`, 기동 20.8 s. 24 의 `KEYCLOAK_ADMIN` 으로 만든 관리자(master)로 admin-cli 토큰 발급 OK. `idem-provisioner`·`idem-session-manager` client_credentials 200, provisioner 로 `GET /admin/realms/idem/clients` → `idem-svc-PERF_RP`(fullScope=false·pkce=S256) 그대로.
- **새 설치**: 빈 스키마(`idem_kc26_fresh`) + 저장소의 `realm-export.json` → `Realm 'idem' imported`, 기동 28.7 s. WARN `Referenced client scope 'openid' doesn't exist. Ignoring` ×2 — 24.0.5 의 import 로그에도 같은 경고 2건(동작 무관). client 4개의 secret 자리표시자(`${IDEM_GATE_KEYCLOAK_CLIENT_SECRET}` 등)가 환경변수로 치환됨(두 서비스 계정 토큰 200), IdP 4개(`social-kakao` 활성, naver·pass·gpki 비활성), 사용자 `poc-testuser`, `bruteForceProtected=true`, 토큰 발급마다 `'Full scope allowed' switch enabled` WARN(26.8 deprecation, §2).
- **구 환경변수 그대로 26 을 띄우면**: compose(24) 의 환경변수 그대로(`KC_HOSTNAME_URL`·`KC_HOSTNAME_ADMIN_URL`·`KC_HOSTNAME_STRICT_HTTPS`·`KC_PROXY=edge`·`KEYCLOAK_ADMIN`, `KC_HTTP_HOST` 없음) 26.8.1 을 띄우면 **기동은 된다**(9.0 초) — 로그 첫 줄에 `WARNING: Hostname v1 options [hostname-admin-url, hostname-url, proxy, hostname-strict-https] are still in use, please review your configuration` 를 남기고 그 옵션을 **무시**한다. 결과 issuer 가 `http://localhost:8089/realms/idem`(gate 공개 URL 이 아니라 자기 주소)이 되어 gate·hub 의 issuer 검증과 기관 RP 의 discovery 가 어긋나고, `start-dev` 는 localhost 에만 바인드된다(§5.2). 즉 태그만 올리면 "뜨지만 틀리게 뜬다" — 환경변수 계약을 같이 바꿔야 한다.

### 5.2 `start-dev` 바인드 (26.6)

`KC_HTTP_HOST` 없이 `start-dev` → `Listening on: http://localhost:8089`(관리 포트는 0.0.0.0:9001). 같은 호스트의 다른 주소(192.0.2.2)에서 `/realms/idem` → connection refused; `KC_HTTP_HOST=0.0.0.0` 인 인스턴스는 200. compose 의 `127.0.0.1:8088:8080` 매핑과 컨테이너 간 통신(`keycloak:8080`)은 둘 다 컨테이너 안 0.0.0.0 바인드가 필요하다 → **필수 변경**.

### 5.3 전체 스택 + 설치본 스모크

registry·authz·gate·hub(1.1.3 boot jar, CI 스모크와 같은 환경변수, `KEYCLOAK_URL=http://localhost:8088`)를 26.8.1 에 붙여 `scripts/ci/install-smoke.sh` ①~⑧ **전부 통과**(discovery issuer = gate, 관리자 로그인, OIDC_RP 프로파일 PUT → client 프로비저닝·secret 회전, gate 프런트의 로그인 화면 프록시·PKCE 사전검사, Handoff 발급·검증, 콘솔 …). 앱 로그에 Keycloak 관련 오류 없음.

### 5.4 표준 OIDC 토큰 교환 부하 (k6 시나리오 12, `scripts/perf/run-k6.sh`)

드라이런(1 VU×1 반복): 체크 7/7, authorize 74 ms·로그인 70 ms·토큰 교환 233 ms·userinfo 38 ms(콜드). 부하(0→5→10 VU, 3분 20초, Keycloak 24.0.5 측정과 같은 조건 — 성능 보고서 §4.1):

| 지표 | Keycloak 24.0.5 (2026-10-15, 보고서 §4.1) | Keycloak 26.8.1 (2026-10-15) | 문턱 |
|---|---|---|---|
| ① authorize(로그인 화면까지) (`oidc_authorize_duration`) | avg 19.6 · p95 **36.9** · p99 45.9 · max 101.7 ms | avg 30.9 · p95 **59.9** · p99 90.3 · max 155.8 ms | — |
| ② 로그인 폼(자격 검증) (`oidc_login_duration`) | avg 397.2 · p95 **614.9** · p99 709.2 · max 907.0 ms | avg 81.6 · p95 **189.6** · p99 262.5 · max 376.1 ms | ✓ p(95)<3000 |
| ③ 토큰 교환 ★(+hub 판정) (`oidc_token_duration`) | avg 32.3 · p95 **48.0** · p99 66.8 · max 247.8 ms | avg 47.0 · p95 **76.8** · p99 102.4 · max 173.3 ms | ✓ p(95)<2000 |
| ④ userinfo (`oidc_userinfo_duration`) | avg 7.1 · p95 **17.8** · p99 30.7 · max 84.2 ms | avg 7.6 · p95 **16.1** · p99 40.9 · max 72.8 ms | — |
| ①~④ 끝-끝 (`oidc_e2e_duration`) | avg 458.5 · p95 **685.8** · p99 778.7 · max 981.0 ms | avg 169.4 · p95 **303.6** · p99 392.8 · max 482.0 ms | ✓ p(95)<5000 |
| 토큰 거부 (`oidc_token_denied_total`) | 0 건 | 0 건 | ✓ count<1 |
| 요청·반복·실패·체크 | 6,525 건 (32.6 req/s) · 반복 1,305 · 실패 0.00% · 체크 100.00% | 9,045 건 (45.2 req/s) · 반복 1,809 · 실패 0.00% · 체크 100.00% | http_req_failed<1 %, checks>99 % |

(샌드박스는 4 vCPU 에 PG·Redis·Keycloak·앱 4종·k6 가 동거한다 — 절대값이 아니라 24 ↔ 26 의 차이만 본다. 결과 `k6/reports/2026-10-15-sandbox-keycloak26/`.)

읽기: 문턱은 전부 통과(1,809 반복, 실패 0, 체크 12,663/12,663). **로그인 폼이 3배 빨라졌다**(p95 615 → 190 ms) — 25 부터 기본 비밀번호 해시가 pbkdf2-sha256(600,000회)에서 argon2 로 바뀌었고 기존 자격은 첫 로그인에 다시 해시되므로(`poc-testuser` 도 그렇게 됐다) CPU 가 적게 든다. **토큰 교환은 조금 느려졌다**(p95 48 → 77 ms, 끝-끝에서는 로그인 절감에 가려진다) — 26 은 사용자 세션을 DB 에 쓰므로(persistent sessions, §2) 로그인·토큰마다 PG 쓰기가 더 있다; 같은 호스트의 PG 와 동거한 측정이라 운영 환경에서는 PG 지연에 따라 다르다. authorize(+23 ms)도 같은 이유로 본다. 어느 쪽도 SLO(토큰 교환 p95 < 2 s)와는 거리가 멀다.

### 5.5 브로커(Keycloak 중계) 로그인 끝-끝 — 시험이 드러낸 Idem 결함 5건

1.1 코어 로그인 프런트에서 `provider=broker:keycloak`(`IDEM_HUB_HANDOFF_LOGIN_BROKER_PROVIDERS=keycloak`, 설치본 기본 `IDEM_HUB_BROKER_MODE=qsign`)으로 진입해 브라우저처럼 홉을 밟았다(`$S/kc26/broker-probe.py` — 진입 302 → 브로커 시작 302 → Keycloak 로그인 화면 200 → 폼 POST 302 → gate 콜백 → hub 바인드 302 → continue 302 → 기관 콜백 `?ticketId=`). **처음에는 두 번째 홉에서 500** 이었고, 고치면 다음 홉이 막히는 식으로 다섯 결함이 차례로 나왔다 — 전부 Keycloak 버전과 무관한 Idem 결함이며, 1.0.0(PR #205, 2026-06-23) 이후 이 경로는 한 번도 끝까지 간 적이 없다. 단위 테스트는 저장소·서비스를 Mock 으로 바꿔 캐시 프록시·설정 기본값·실제 DB 를 지나지 않았고, 설치본 스모크·k6·IT 는 MOCK(SPI) 제공자와 OIDC_RP 만 밟는다.

| # | 결함 | 증상 | 수정(PR-G2-10) |
|---|---|---|---|
| 1 | hub `ProviderConfigRepository.findByCode` 의 `@Cacheable(unless = "#result.isEmpty()")` — Spring 은 `Optional` 반환값의 `#result` 를 **껍질을 벗긴 값**으로 넘긴다(비면 null) | 비면 `null.isEmpty()`(EL1011E), 있으면 `ProviderConfig.isEmpty()` 없음(EL1004E) → **브로커 인증 시작이 항상 500**(모든 브로커 모드·모든 제공자) | `unless = "#result == null"`. 그 뒤에 숨어 있던 두 번째 결함 — 캐시 값이 타입 정보 없이 저장돼 적중 시 `LinkedHashMap` 이 돌아와 캐스트 실패 — 도 같이: `ProviderConfig` 에 `@Jacksonized` 빌더, `provider-config` 캐시에 타입 지정 직렬화기 |
| 2 | hub `OidcCompleteController`(gate 의 완료 콜백)가 returnUrl 을 `idem.hub.fe.allowed-return-urls`(기본 localhost:3000/3001, 설치본은 설정하지 않음)로만 검사 | 1.1 로그인 프런트의 returnUrl 은 hub 자신의 `…/handoff/login/continue?req=` → `INVALID_RETURN_URL` 400 → gate 오류 화면 | hub 자신의 continue 주소는 통과(`isOwnLoginContinueUrl`) — 기관 콜백은 continue 가 저장된 요청(진입 때 화이트리스트 검증)으로 보낸다 |
| 3 | gate `KeycloakCallbackService` 가 hub 완료 콜백에 `identifierHash` 만 보내고 주체 키(`subjectScheme`/`subjectKey`)를 안 보낸다 | hub 는 주체 키 없는 인증 결과로 세션을 발급하지 않는다(`allow-ciless-identity=false` 가 운영 기본) → `E-IDO-…` 거부 | Keycloak `sub` 를 `EXTERNAL_SUB` 주체 키로(OIDC_RP 경로의 등록 기준과 같다) |
| 4 | gate `AuthResultJpaEntity.claims`·`QSignOutboxRecord.payload` 가 `jsonb` 열에 String 으로 바인딩 | PostgreSQL `column is of type jsonb but expression is of type character varying` → `auth_result`·outbox INSERT 실패(값이 null 이어도) → 인증 결과 저장 트랜잭션 롤백 | `@JdbcTypeCode(SqlTypes.JSON)`(registry·hub 의 jsonb 열은 이미 그렇게 되어 있었다) |
| 5 | gate `idp_provider` 시드에 `KEYCLOAK_OIDC`(Keycloak 계정 직접 로그인)·`PASS_OIDC`·`GPKI_OIDC`(realm 의 IdP 별칭에서 gate 가 만드는 코드)가 없다 | `fk_auth_result_provider` 위반 → 저장 실패 | gate Flyway **V6** 로 행 추가 |

수정 뒤: 26.8.1 에서 끝-끝 2회 통과(두 번째는 캐시 적중·registry 기존 사용자 경로), gate `auth_result`(`KEYCLOAK_OIDC`, `STANDARD_OIDC_KEYCLOAK_OIDC`, SUCCESS)·outbox(`AUTH_COMPLETED`) 행 확인, hub `handoff_audit` CONSUMED. 설치본 버전 **24.0.5** 로 되돌려(원래 `keycloak` 스키마) 같은 프로브 2회 통과 — 수정은 Keycloak 버전과 무관하게 통한다. 회귀 검사: hub IT `BrokerLoginIntegrationTest`(q-sign 모드 — gate 를 WireMock 으로, 내부 서명·EXTERNAL_SUB 주체 키로 완료 콜백 → 바인드 → continue → verify; 캐시 적중·빈 결과 미캐시), gate IT `AuthResultJpaPostgresIntegrationTest`(실제 PostgreSQL 에 Flyway 적용 뒤 `claims` null/JSON·outbox payload·V6 FK), UT `OidcCompleteControllerTest`(+2)·`KeycloakCallbackServicePkceIssuerTest`(+1). 시험 항목표 **D-19**.

관찰(결함 아님): hub·gate 가 `kc_idp_hint=keycloak` 을 보내면 26 은 `Provider not found or not enabled for realm keycloak` WARN 을 남기고 로그인 폼으로 간다(24 는 조용) — 별칭 매핑이 없는 브로커 이름이면 `kc_idp_hint` 를 빼는 것이 깔끔하다 — **PR-G2-12 에서 정리**(hub·gate 모두 매핑이 있을 때만 붙인다).

## 6. 단계 계획과 공수

| 단계 | 내용 | 공수(AI) |
|---|---|---|
| 1 | 설치본 계약: compose(`26.8`, hostname v2, proxy-headers, bootstrap admin, `KC_HTTP_HOST`) · Helm(태그·환경변수·프로브 9000·`values.yaml` 주석) · CI 스모크 잡 · 개발 compose 2종 · `install.env.example`·`gen-install-env.sh`(변수명) · 문서(install·install-inputs·설치 매뉴얼·ADR-014·application.yml 주석) · realm-export 내부 client `fullScopeAllowed=false`(선택) | 1일 |
| 2 | 리허설: CI 스모크(26 이미지)·K8s 리허설(auto-build 메모리 — 26 은 더 쓸 수 있다, 2Gi 재확인)·오프라인 번들(이미지 목록)·`third-party-scan` 첫 결과 기록 | 1일 |
| 3 | 업그레이드 절차: `CHANGELOG` 업그레이드 메모(세션 유실·DB 백업·변수명)·운영 가이드·`scripts/upgrade/`(변수명 치환 도우미, 선택) | 0.5일 |
| 4 | 사용자 리허설 1회(설치본 올림 → 스모크 → 기관 OIDC_RP 재로그인 확인) | 사용자 |

Spring Boot 4 이행(`spring-boot-4-assessment.md`)과 **독립** — 코드 변경이 없어 어느 쪽을 먼저 해도 된다. 둘을 한 릴리스에 섞지 않는 편이 리허설 범위를 좁힌다.

## 7. 권고와 결정 사항 (2026-11 회차)

1. **1.2 에서 Keycloak 26.8.x 로 올린다** — 설치본 환경변수 계약이 바뀌므로 1.1.x 패치가 아니다(플랜 §4). 작고(코드 변경 없음) 독립적이며 취약점 수가 가장 많은 이미지라 Spring Boot 4 보다 먼저 한다. GS 시험 대상을 1.2 로 잡으면 26 이 시험 대상이 된다.
2. 그 전 1.1.x 설치본은 `keycloak:24.0`(= 24.0.5) 그대로 — 24.0 라인에 더 나올 수정판이 없고, Keycloak 이 gate 뒤에 숨어 있어(관리 콘솔 로컬 포트, `/clients-registrations`·SAML 미사용) 노출면이 좁다는 점을 점검 문서 §4.4 에 유지한다.
3. 올릴 때 태그는 **`26.8`**(마이너 고정, 패치 자동) — 24 와 같은 규칙. `third-party-scan` 이 자동으로 따라온다. **→ 2026-11-12 에 바꿨다: `26.8.2` 패치 고정(§8.3).** 롤링 태그가 26.8.1 → 26.8.2 로 넘어가며 main 이 깨졌다.
4. 업그레이드 창에 Keycloak 세션이 전부 사라진다는 것을 운영 절차에 적는다(기관 RP 사용자 재로그인, hub FE 세션·티켓은 무관). 25 경유(persistent sessions 보존)는 하지 않는다.
5. 2026-11 회차에서 확인할 것: 26.8.x 최신 패치, `third-party-scan` 의 26 이미지 결과(24 대비 jar 77 → ?), Spring Boot 4 와의 릴리스 순서.

## 8. 추록 — 26.8.2 대응과 최종 계약 리허설 (2026-11-12, 1.2 PR-1.2-2)

§0~§7 은 26.8.1 기준(2026-10-15)이다. PR-1.2-1(#292, 2026-10-29)이 §3 의 계약을 설치본에 넣었고 그 PR 의 CI 는 26.8.1 로 초록이었다. 머지(2026-11-12) 뒤 main run 43160969761 의 `k6 Smoke Test`·`K8s 실배포 리허설` 이 스모크 ③ 에서 실패했다(`E-IDO-122 … Keycloak Admin GET …/clients?clientId=… → 401`) — 그 사이 26.8.2 가 나와 롤링 태그 `26.8` 이 따라갔기 때문이다(main run 의 이미지 jar 이름이 `keycloak-…-26.8.2.jar`). 이 절은 26.8.2 가 바꾼 것, Idem 의 대응, 태그 정책 변경, 최종 계약으로 다시 돈 리허설(새 설치·업그레이드·롤백)을 적는다.

### 8.1 26.8.2 가 바꾼 것 (26.8.1 ↔ 26.8.2 소스 대조)

Maven Central 의 sources jar(`keycloak-services`·`keycloak-server-spi-private`·`keycloak-model-storage-private`·`keycloak-server-spi`·`keycloak-core`, 두 버전)를 받아 비교했다. `keycloak-services` 87개 파일이 다르다 — Idem 에 닿는 것만.

| # | 변경 | 근거(소스) | Idem 영향 |
|---|---|---|---|
| ① | **관리 REST API 의 scope·audience 검사** — bearer 토큰에 audience `{공개(frontend) URL}/admin/realms/{realm}` 과 scope `kc-admin-api` 가 있어야 한다. 없으면 401 | `AdminRoot`: `authenticator.setAudience(getAdminApiAudience(…))`, `TokenUtil.hasScope(token.getScope(), "kc-admin-api")` 아니면 `NotAuthorizedException`. 새 client scope `kc-admin-api`(mapper `oidc-admin-api-audience-mapper`)는 realm 기본 scope 생성(`OIDCLoginProtocolFactory.createDefaultClientScopesImpl`)과 `MigrateTo26_8_2` 가 만들고 **`admin-cli`·`security-admin-console` 에만** 붙인다. 끄는 설정(`Config.scope("admin")` 의 `audienceCheckEnabled`, 기본 true)이 있으나 쓰지 않는다 | ✗ **`idem-provisioner`·`idem-session-manager` 의 토큰이 401** — hub 프로비저닝(OIDC_RP 프로파일 저장·상태 조회·secret 회전)과 gate 의 Keycloak 세션 종료(hub 가 시작하는 단일 로그아웃)가 전부 막힌다. 토큰 발급 자체는 200 이라 기동·헬스로는 보이지 않는다 |
| ② | **protocol mapper 허용 목록** — `manage-realm` 이 없는 관리자가 mapper 를 만들기·고치기·지우기 하려면 그 mapper 종류가 realm 속성 `allowedProtocolMapperTypesForClientAdministrators`(쉼표 구분)에 있어야 한다. 속성이 없거나 비면 전부 403 | `ProtocolMappersResource.requireManageProtocolMappers`(`auth.realm().canManageRealm()` 이면 통과, 아니면 허용 목록) — `ClientsResource.createClientModel`(표현에 `protocolMappers` 가 있을 때)·`ClientResource.update`(바뀐 mapper)·`ProtocolMappersResource` 의 생성·수정·삭제·client scope 쪽에서 부른다. 마이그레이션은 이 속성을 넣지 않는다 | ✗ **새 기관 OIDC_RP 온보딩이 403** — hub 는 client 를 만들 때 mapper 둘(`oidc-usersessionmodel-note-mapper` = `identity_provider`, `oidc-hardcoded-claim-mapper` = `idem_service`)을 표현에 넣는다(`OidcRpClientProvisioner`). 이미 만든 client 의 갱신(표현에 mapper 없음)·secret 회전은 된다 — 그래서 업그레이드 직후에는 드러나지 않고 다음 온보딩에서 터진다 |
| ③ | 동적 client 등록(DCR) 정책 추가 — "Default Roles Disabled"(anonymous·authenticated), "Full Scope Disabled"(authenticated) | `MigrateTo26_8_2.addPolicyIfMissing` | ✓ Idem 은 DCR(`/clients-registrations`)을 쓰지 않는다 |
| ④ | 로그아웃 엔드포인트가 `id_token_hint` 를 일찍 검증하고(낡은 토큰은 400), 로그아웃 세션의 이전 값을 지운다. 사용자 세션과 루트 인증 세션을 함께 지우는 경로 정리 | `LogoutEndpoint`, `AuthenticationManager.removeUserSessionAndRootAuthSession` | ✓ D-8 SLO 프로브 13/13(§8.4) — RP-initiated 로그아웃·back-channel·세션 종료 그대로 |
| ⑤ | `acr_values` 가 필수가 아닐 때의 LoA 계산, 오프라인 세션 정리, 관리 권한(역할 부여 검사) 세부 | `AuthorizationEndpoint`, `UserSessionManager`, `fgap/RolePermissions` | ✓ 사용하지 않거나 영향 없음(스모크·프로브 통과) |

①·② 는 둘 다 보안 강화다 — manage-clients 만 가진 관리자가 mapper 로 임의 claim(예: audience)을 넣어 권한을 넓히는 길을 막는다. 그래서 끄지 않고 조건을 맞췄다.

### 8.2 대응 (PR-1.2-2)

| 어디 | 무엇 |
|---|---|
| `infra/docker/keycloak/realm-export.json` = `infra/helm/idem/files/realm-export.json`(새 설치) | 서비스 계정 client 둘에 `defaultClientScopes: [web-origins, acr, roles, profile, basic, email, kc-admin-api]` — realm 기본 scope 에 `kc-admin-api` 를 더한 것(표현에 scope 목록을 적으면 realm 기본값을 대신하므로 전부 적는다; 업그레이드한 realm 에 스크립트를 돌린 결과와 같은 집합). realm `attributes.allowedProtocolMapperTypesForClientAdministrators` = hub 가 쓰는 두 종류. import 순서상 기본 scope 생성이 client import 보다 먼저라 `kc-admin-api` 를 이름으로 참조할 수 있다(`RealmManager.importRealm`). `manage-realm` 은 주지 않는다 |
| `scripts/upgrade/keycloak-realm-1.2.sh`(기존 realm) | Keycloak 관리자 계정으로 ① 두 client 에 `kc-admin-api` 를 기본 scope 로 붙이고 ② realm 속성에 두 mapper 종류를 더한다(지금 속성 맵을 읽어 값 하나만 더해 보낸다 — realm 갱신은 보낸 `attributes` 맵으로 속성을 교체하기 때문) ③ 서비스 계정 토큰으로 실제 호출해 확인(client 조회 200 · mapper 둘을 넣은 시험 client 생성 201 → 삭제 204 · 사용자 조회 200). 멱등, 26.8.2 미만이면 "할 일 없음". 비밀·토큰은 출력하지 않는다 |
| hub `KeycloakAdminClient` | 관리 API **401** → 거절당한 캐시 토큰을 버리고(다음 호출이 새 토큰으로 — 운영자가 스크립트를 돌린 뒤 재기동·만료 대기가 없다) 오류 문구에 조치를 싣는다. **403** → mapper 허용 목록 안내. 문구는 콘솔의 E-IDO-122 와 로그에 그대로 보인다 |
| gate `KeycloakLogoutService` | 401 → 캐시 폐기 + ERROR 로그 안내(결과는 종전대로 `FAILED` → 502 → hub 재시도 큐) |
| 설치본 스모크 `scripts/ci/install-smoke.sh` | **④′** — gate 내부 세션 종료 API 를 없는 세션 ID 로 불러 `idem-session-manager` 토큰이 관리 API 를 왕복하는지(204 `NOT_FOUND`), 서명 없는 호출이 401 인지. ③ 은 종전대로 `idem-provisioner` 의 client 생성(mapper 포함)을 본다 — 두 서비스 계정을 다 밟는다. K8s 리허설도 같다 |
| 이미지 태그 | §8.3 |

### 8.3 태그 정책 — 패치까지 고정한다 (§7.3 의 권고를 바꾼다)

§7.3 은 "`26.8`(마이너 고정, 패치 자동)" 을 권했고 PR-1.2-1 이 그렇게 넣었다. 2주 만에 패치 릴리스가 관리 API 의 의미를 바꿔 프로비저닝과 세션 종료를 막았다 — PR 에서 검증한 것(26.8.1)과 main·설치 현장이 받는 것(26.8.2)이 달라질 수 있다는 뜻이다. 그래서 **`quay.io/keycloak/keycloak:26.8.2`** 로 패치까지 고정한다.

- 고정한 곳: `compose.install.yml`(단일 출처)·개발 compose 2종·Helm `keycloak.image.tag`·CI(스모크 `docker run`·`third-party-scan` 매트릭스·Helm 회귀 검사)·현재 상태 문서 — 11곳. `docs-lint` **K 규칙**이 같은 값(과 X.Y.Z 형식)임을 CI·pre-commit 에서 강제한다. §3 의 "`third-party-scan` 은 compose 의 태그를 읽는다" 는 틀린 서술이었다(매트릭스는 고정 문자열) — 지금은 K 규칙이 묶는다.
- 비용: Keycloak 보안 패치가 자동으로 오지 않는다. 월 1회 점검에서 `third-party-scan`(main push 마다 고정한 그 태그를 긁는다)과 상류 릴리스 노트를 보고 패치 올림 PR 을 낸다 — 한 PR 에서 11곳을 바꾸고 스모크·K8s 리허설·오프라인 번들이 그 패치로 돈다.
- ~~오프라인 번들·GS 시험 대상의 구성품이 빌드 날짜에 따라 달라지지 않는다.~~ **→ 2026-11-13 정정: 달라진다(기반 이미지 층).** 아래.

**패치 태그도 다시 밀린다 (2026-11-13 관찰, PR-1.2-3b)**: 하루 사이에 같은 `quay.io/keycloak/keycloak:26.8.2` 가 다른 이미지가 됐다.

| 언제 | 어디서 | digest | 기반 OS | Keycloak jar |
|---|---|---|---|---|
| 2026-11-12 | main run 43160969761(`26.8`)·PR #293 run 43173131527(`26.8.2`, 스모크·오프라인 번들) | `sha256:723b1d6e254c…5e41` | redhat 9.8 | 479개(`keycloak-admin-cli-26.8.2.jar` …) |
| 2026-11-13 | main run 43353550972(`26.8.2`, 스모크) | `sha256:c68e286cacd8…bbf0` | redhat 9.9 | 479개 — 이름·판이 전부 같다 |

- 바뀐 것은 기반 이미지(UBI 9.8 → 9.9)뿐이고 Keycloak 은 그대로 26.8.2 다(Trivy 표의 jar 479행 대조). 상류가 패치 태그를 기반 이미지 갱신으로 다시 민다는 뜻이다 — 이번 main 을 깬 것 같은 **동작 변화는 태그 고정으로 막히지만, 이미지 내용은 고정되지 않는다**.
- 좋은 쪽: 기반 OS 의 보안 수정이 PR 없이 들어온다(`postgres`·`redis` 롤링 태그와 같은 효과). 나쁜 쪽: CI 가 검증한 이미지와 설치 현장이 받는 이미지가 층 단위로 다를 수 있고, 다른 날 만든 오프라인 번들끼리 Keycloak 이미지가 다르다.
- **결정 사항(사용자)**: (가) 지금처럼 태그 고정 + **digest 를 기록**한다(오프라인 번들의 `MANIFEST.txt`(이미지 ID)·`SHA256SUMS` 와 CI 로그(`docker pull` 의 digest)에 이미 남는다 — GS 시험 대상은 번들 하나로 고정된다) / (나) digest 까지 고정한다(`26.8.2@sha256:…`, 11곳 + K 규칙 — 기반 OS 수정도 월 점검의 올림 PR 로만 들어온다). 권고는 (가): 시험 대상은 번들로 고정되고, 기반 OS 수정이 늦지 않는다. 1.2.0 버전 커밋 때 그 시점의 digest 를 형상 관리 문서에 적는다.

### 8.4 리허설 기록 (샌드박스, 2026-11-12)

환경: 4 vCPU·16 GB 단일 호스트, PostgreSQL 16.15·Redis 로컬. Keycloak 은 배포본 zip(Maven Central `keycloak-quarkus-dist` 24.0.5·26.8.2)을 `start-dev --import-realm` 으로 — 환경변수는 compose 파일(1.1.7 은 태그 `v1.1.7` 의 것, 1.2 는 작업 트리의 것)의 keycloak 서비스 `environment` 를 기계적으로 뽑아 썼다. 앱은 부트 jar + `scripts/ci/start-apps.sh`(CI 스모크와 같은 레시피). 샌드박스에는 Docker 데몬이 없다 — 컨테이너 이미지·compose·Helm(production `start`)은 CI 가 본다(§8.4 e).

**(a) 새 설치 — 26.8.2, 1.2 계약, 수정한 realm export**

- 빈 DB(`init-db.sql`)·빈 Redis → Keycloak realm UP **22 s**(`Realm 'idem' imported`, started in 20.5 s; WARN 은 종전과 같은 `Referenced client scope 'openid' doesn't exist. Ignoring` ×2) → 앱 4종 UP **33 s** → 설치본 스모크 **74 ✅**(③ client 생성·secret 86자, ④′ 204 `NOT_FOUND`) → 기관 RP 2개 생성 → **D-8 SLO 프로브 13/13** → `keycloak-realm-1.2.sh` 는 "바꾼 곳 0".
- 서비스 계정 client 의 기본 scope = `acr, basic, email, kc-admin-api, profile, roles, web-origins`. 토큰: `scope = "kc-admin-api profile email"`, `aud = [{KC_HOSTNAME}/admin/realms/idem, realm-management]`. audience 는 공개(frontend) URL 로 계산된다 — hub·gate 가 내부 주소(`KEYCLOAK_URL`)로 불러도 `KC_HOSTNAME` 이 고정이면 발급·검증이 같은 값을 쓴다(설치본 계약).
- 대조 — 수정 **전** export 를 26.8.2 에 새로 import: 두 서비스 계정 토큰은 200 이지만 관리 API 가 401(CI 실패 그대로). scope 만 붙이고 속성이 없으면: 조회 200, mapper 를 넣은 `POST /clients` 403.
- 하위 호환 — 수정한 export 를 **24.0.5** 에 import: WARN `Referenced client scope 'basic' doesn't exist. Ignoring`·`… 'kc-admin-api' …`(client 마다) 뒤 정상, 관리 API 200. 모르는 realm 속성은 그대로 저장된다.

**(b) 업그레이드 — 1.1.7 + Keycloak 24.0.5 → 1.2 + 26.8.2, 같은 DB**

준비(1.1.7 상태를 처음부터): 1.1.7 계약·1.1.7 realm export·1.1.7 부트 jar 로 새 설치 → 스모크 74 ✅(secret 32자) → 기관 RP 2개 + SLO 13/13 → 로그인 세션 1개 유지 → `scripts/ops/backup.sh`(1 s, 856 KB, 표 167).

| 단계 | 결과 |
|---|---|
| 1. 앱·Keycloak 정지 | 앱 10~12 s, Keycloak 3~4 s |
| 2. Keycloak 26.8.2(1.2 계약) 기동 | realm UP **12~14 s**(4회; 첫 회는 배포본의 최초 빌드가 겹쳐 33 s) — 모델 마이그레이션 `25.0.0 → 26.0.0 → 26.1.0 → 26.2.0 → 26.3.0 → 26.4.0 → 26.4.3 → 26.6.1 → 26.6.2 → 26.7.0 → 26.8.0 → 26.8.2`(§5.1 의 26.8.1 보다 한 단계 더), `Realm 'idem' already exists. Import skipped`, Bootstrap 6.9~7.8 s, started in 11.1~12.4 s. `keycloak` 스키마 92 → 101 표, `migration_model` 24.0.5 → 26.8.2 |
| 3. 앱(1.2 빌드) 기동 | UP 30~31 s |
| 4. 업그레이드 전 세션 | authorize → 로그인 화면(SSO 없음), `refresh_token` → 400 `invalid_grant`(Session not active), userinfo 401, Keycloak 사용자 세션 0 — **세션 유실 확인**(§2 26.0, 예상대로) |
| 5. 스크립트 전 | 새 기관 프로파일 PUT → 503 `E-IDO-122 … → 401 UNAUTHORIZED — Keycloak 이 서비스 계정 토큰을 거부했다. Keycloak 26.8.2 이상은 … scripts/upgrade/keycloak-realm-1.2.sh …`. gate 내부 세션 종료 → 502 `FAILED` + ERROR 로그에 같은 안내 |
| 6. `keycloak-realm-1.2.sh` | **1 s** — scope 2건 부착, 속성 추가, 확인 3건 통과. realm 표현을 앞뒤로 받아 대조: 바뀐 것은 `attributes.allowedProtocolMapperTypesForClientAdministrators` 하나 |
| 7. **앱 재기동 없이** | 새 기관 온보딩(client 생성 + mapper) 200 → 스모크 **74 ✅**(secret 86자) → gate 내부 세션 종료 204(`NOT_FOUND`; 실제 세션은 sid 로 `REVOKED_SESSION`, sub 로 `REVOKED_USER` — 뒤이어 그 세션의 SSO·refresh·userinfo 가 끊긴 것 확인) → **SLO 13/13**(업그레이드 전에 만든 기관, 32자 secret 그대로) |
| 8. 속성만 없는 상태(scope 는 있음)를 만들어 새 기관 온보딩 | 503 `E-IDO-122 … POST …/clients → 403 FORBIDDEN — … allowedProtocolMapperTypesForClientAdministrators …` → 스크립트를 다시 돌리면 해소 |

- 26 은 사용자 세션을 DB 에 둔다(§2): 로그인 뒤 Keycloak 만 재기동(6.7 s) → 같은 쿠키로 authorize 302(로그인 화면 없음)·같은 `sid`·`refresh_token` 200·userinfo 200. 24 는 재기동에도 세션이 사라졌다.
- 그 밖의 관찰: `sid` 는 24자(UUID 아님, §2 26.5), 쿠키는 `AUTH_SESSION_ID`·`KC_AUTH_SESSION_HASH`·`KEYCLOAK_IDENTITY`·`KEYCLOAK_SESSION`(24 의 `*_LEGACY` 쌍 없음), 26 에서 로그인한 계정의 비밀번호 해시는 `pbkdf2-sha512`(210,000) → `argon2` 로 다시 저장된다.

**(c) 롤백 — 백업 복구가 유일한 길**

- 앱·Keycloak 정지 → `scripts/ops/restore.sh`(업그레이드 전 덤프, **2 s**) → Keycloak 24.0.5(1.1.7 계약) realm UP 7 s → 1.1.7 부트 jar UP 30 s → 스모크 74 ✅ → SLO 13/13.
- **이미지 태그만 되돌리면 안 된다**: 26.8.2 로 올라간 스키마(사본)에 24.0.5 를 붙이면 오류·경고 없이 기동하고 realm 엔드포인트도 200 을 준다(10 s). 그러나 26 에서 로그인한 계정(관리자)은 `invalid_user_credentials` 로 로그인이 안 된다 — 해시가 argon2 로 바뀌어 있다. 서비스 계정 토큰은 발급된다. 버전 검사가 막아 주지 않으므로 절차로 막는다(설치 매뉴얼 §5).

**(d) 로그인 화면 (gate 경유, 헤드리스 브라우저)**

- 26.8.2 의 로그인 테마는 `keycloak.v2`(PatternFly 5) — 24 의 화면과 모양이 다르다. realm 에 `loginTheme` 을 지정하지 않아 기본값을 따르며, 새 설치와 업그레이드한 realm 의 화면이 픽셀 단위로 같다. 폼 `#kc-form-login`(스모크 ④·SLO 프로브가 찾는 것)·필드 `username`·`password`·`credentialId`, 폼 action 은 gate 공개 URL, 테마 리소스 실패 0(전부 gate 프록시 경유).
- 사용자 매뉴얼 §2.1 은 이 화면의 캡처를 GS 시험 환경에서 넣기로 되어 있다 — 지금 매뉴얼에 Keycloak 화면 캡처는 없다(바꿀 그림 없음).

**(f) Keycloak 중계 로그인 (시험 항목 D-19) — 26.8.2 재실행**

hub 를 `IDEM_HUB_HANDOFF_LOGIN_BROKER_PROVIDERS=keycloak` 로 띄우고 로그인 프런트 `provider=broker:keycloak` 로 브라우저처럼 7홉을 밟았다(업그레이드한 realm): 진입 302 → 브로커 시작 302 → Keycloak 로그인 화면 200 → 폼 POST 302 → gate 콜백 302 → hub 바인드 302 → continue 302 → 기관 콜백 `?ticketId=&state=`. **2회 통과** — verify `APPROVED`, 재검증 409 `E-IDO-102`, `feSessionId` 쿠키, gate `auth_result`(`KEYCLOAK_OIDC`·`STANDARD_OIDC_KEYCLOAK_OIDC`·SUCCESS)·hub `handoff_audit` CONSUMED. Keycloak 과의 계약(code 교환·ID Token·`sid`·`sub`)은 26.8.2 에서도 같다 — gate 는 주체를 ID Token 의 `sub` 에서 읽고, ID Token 은 `basic` scope 가 없어도 `sub` 를 싣는다(`TokenManager`).

단, 세 번째 홉에서 브라우저가 간 곳은 gate 공개 URL 이 아니라 **Keycloak 의 내부 주소**(`KEYCLOAK_URL`)였다 — 샌드박스는 Keycloak 이 localhost 라 닿는다. 설치본 배치에서는 닿지 않는다: §8.6 #7.

**(e) CI 가 본 것**

PR #293 의 첫 run 43168960722(커밋 `c56974f`, 러너 `ubuntu-26.04`) — 샌드박스가 못 보는 컨테이너 이미지·Helm 을 본다.

- `k6 Smoke Test`: 컨테이너 `quay.io/keycloak/keycloak:26.8.2`(compose 계약, H2) started in 14.2 s·realm import → 앱 UP → **설치본 스모크 통과**(③ secret 86자, ④′ 204) → 백업·복구 → 운영 DB 복구 리허설까지 통과. 그 뒤 `k6 설치` 단계에서 멈췄다 — Keycloak 과 무관한 러너 이미지 문제(같은 날 `ubuntu-latest` 가 26.04 로 넘어가 apt 키링 형식이 깨짐)이고 같은 PR 에서 고쳤다(CHANGELOG).
- `K8s 실배포 리허설` ✅ — kind v0.31·K8s 1.35·Helm 4.3: up 71 s · **install 100 s**(Pod 6개 Ready — Keycloak production `start` + realm import, 프로브 9000; 24 때 84 s, 26.8.1 때 104 s) · 스모크 11 s(TLS Ingress 경유 ①~⑧c, **④′ 포함**) · 운영 전환 upgrade 56 s · rollback 45 s · down 13 s, 합계 296 s. Keycloak 의 메모리 한도는 2Gi 그대로다(§6 의 "auto-build 메모리 재확인" — OOM 없이 Ready).
- `오프라인 설치본` ✅ — 서드파티 목록이 `quay.io/keycloak/keycloak:26.8.2`, digest `sha256:723b1d6e…`(main run 이 `26.8` 로 받은 것과 같다 — 고정한 태그가 그 이미지다), 번들 808 MB → SHA256 → load → MANIFEST 8종 대조.
- Docker Build Check 10종·Helm Lint(회귀 검사의 태그 `26.8.2`)·Build & Unit Test·문서 정합성(K 규칙) ✅.

수리 뒤 **run 43171446108(커밋 `514ab14`) 은 잡 전부 성공** — `k6 Smoke Test` 가 k6 스모크·hub prod 프로파일 단계까지 끝났고, K8s 리허설·오프라인 번들도 다시 통과했다. 시험 항목표의 Keycloak 관련 행이 이 run 을 인용한다.

### 8.5 `third-party-scan` — 26 의 첫 결과

main `8ad3cbe` run 43160969761(Trivy v0.76.1, 2026-11-12): `quay.io/keycloak/keycloak:26.8`(= 26.8.2, redhat 9.8) — **OS 0 · Java jar 1**(HIGH 1: `com.google.guava:guava` 33.6.0-jre, CVE-2026-102554, 수정판 33.7.2). 24.0(= 24.0.5)은 OS 22(HIGH 21·CRITICAL 1) + jar 77(HIGH 71·CRITICAL 6)이었다 — §1 이 이 올림의 이유로 든 숫자다. guava 1건은 상류 이미지의 구성품이라 다음 패치(26.8.x)를 기다린다(점검 문서 2회차 §8).

### 8.6 관찰 — 설치 realm (이번 PR 밖, 다음 PR 후보)

로그인 화면을 확인하다 본 것들이다. 전부 24 시절부터 있던 것으로 26 올림과 무관하지만, 설치본(compose)과 Helm 이 같은 `realm-export.json` 을 import 하므로 **운영 설치에도 들어간다**. 고치는 일은 이 PR 에 넣지 않았다(범위·시험 도구 영향) — 플랜 §5.5.

| # | 관찰 | 영향 | 제안 |
|---|---|---|---|
| 1 | 시험 계정 `poc-testuser` 가 realm export 에 있고 비밀번호가 저장소에 평문으로 있다 | **기본 계정**. 이 계정으로 표준 로그인 화면에서 로그인하면 OIDC_RP 기관에 들어가고 hub 판정도 `idem_state=APPROVED` 다(SLO 프로브·k6 12 가 그렇게 돈다). k6 README 는 "설치 검증용 realm 에만 있다" 고 적었지만 설치본·Helm 이 쓰는 파일이 그 파일이다 | realm export 에서 뺀다. CI 스모크는 쓰지 않는다 — SLO 프로브·k6 12·성능 보고서는 시험 때 관리 API 로 만들게 바꾼다. 기존 설치본은 삭제 안내(업그레이드 절차) |
| 2 | realm `displayName` 이 "OnePass 통합인증"(역할 설명도 OnePass) | 로그인 화면 머리글·브라우저 제목에 옛 제품명 — 제품 설명서의 이름(Idem)과 다르다 | "Idem 통합인증" 으로. 기존 realm 은 관리 콘솔 Realm settings 또는 스크립트 |
| 3 | IdP `social-kakao` 가 자리표시자 client id(`your-kakao-client-id`)로 `enabled: true` | 기본 설치의 로그인 화면에 동작하지 않는 "카카오 로그인" 버튼 | 기본 비활성(나머지 셋처럼), 켜는 법은 문서로 |
| 4 | 국제화 미설정 — 로그인 화면이 영어("Sign in to your account") | 한국어 제품의 표준 로그인 화면이 영어 | `internationalizationEnabled`·`supportedLocales [ko, en]`·`defaultLocale ko` |
| 5 | `sslRequired: none` | TLS 종단이 프록시라는 전제(ADR-014). 값의 근거가 문서에 없다 | `external` 검토 + 근거 기록 |
| 6 | 내부 client 4개 `fullScopeAllowed: true` | 26.8 deprecation WARN(토큰 발급마다) — §2 에서 이미 적은 것 | false + 필요한 scope 매핑(선택) |
| 7 | **Keycloak 중계 로그인(D-19)의 인가 URL 이 Keycloak 내부 주소로 만들어진다** — gate `KeycloakAuthUrlController` 가 `KeycloakProperties.authorizationEndpoint()`(= `KEYCLOAK_URL`)로, hub 의 `keycloak` 브로커 모드(`BrokerService`)도 `KEYCLOAK_BASE_URL` 로 (설치 realm 이 아니라 코드 쪽 관찰) | 설치본은 `KEYCLOAK_URL=http://keycloak:8080`(컨테이너 내부 이름)이라 **브라우저가 그 주소로 보내지면 닿지 않는다**. 샌드박스·CI 는 Keycloak 이 localhost 여서 통과해 왔다(§5.5 의 2026-10-15 프로브도 같은 배치). 주소만 gate 공개 URL 로 바꿔서는 안 된다 — gate 공개 프런트(`/realms/idem/protocol/openid-connect/auth`)는 `idem-gate`·`idem-hub` client 를 400 `unauthorized_client` 로 막는다. 이 경로는 기본 꺼짐(`IDEM_HUB_HANDOFF_LOGIN_BROKER_PROVIDERS` 비어 있음, 브로커 모드 `qsign`)이라 기본 설치의 로그인·OIDC_RP 흐름에는 영향이 없다 | 브라우저용 인가 URL 을 gate 공개 URL 로 만들고 프런트가 내부 client 의 브로커 요청을 통과시키는 경로를 둔다 → 시험 항목 D-19 를 설치본 배치(compose)로 1회. 결정 전까지는 지원 배치를 문서에 적는다 |

