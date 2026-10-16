# Keycloak 26 올림 영향 평가 (2026-10-15, 1.1.3 기준)

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
| `.github/workflows/ci.yml` k6 스모크 잡(`docker run … keycloak:24.0 start-dev`) | compose 와 같은 24 환경변수 | compose 와 같이 바꾼다(`--network host` 라 바인드 주소는 문제가 안 되지만 환경변수 계약은 compose 와 같게). `third-party-scan` 은 compose 의 이미지 태그를 읽으므로 자동으로 26 을 긁는다 |
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
3. 올릴 때 태그는 **`26.8`**(마이너 고정, 패치 자동) — 24 와 같은 규칙. `third-party-scan` 이 자동으로 따라온다.
4. 업그레이드 창에 Keycloak 세션이 전부 사라진다는 것을 운영 절차에 적는다(기관 RP 사용자 재로그인, hub FE 세션·티켓은 무관). 25 경유(persistent sessions 보존)는 하지 않는다.
5. 2026-11 회차에서 확인할 것: 26.8.x 최신 패치, `third-party-scan` 의 26 이미지 결과(24 대비 jar 77 → ?), Spring Boot 4 와의 릴리스 순서.
