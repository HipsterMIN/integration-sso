# Third-party notices

Idem 은 Apache-2.0 으로 배포되며 아래 제3자 구성요소를 사용한다. 이 목록은 **직접 의존성** 기준이다. 전체 전이 의존성은
`./gradlew :<module>:dependencies --configuration runtimeClasspath` 와 `editions/idem-kr-portal/frontend/yarn.lock`·`idem-console-admin/package-lock.json` 으로 확인한다.
런타임에 함께 배포되지만 이 저장소에 포함되지 않는 것(Keycloak·PostgreSQL·Redis·Kafka·Nginx 컨테이너 이미지, 벤더 SDK)은 각자의 라이선스를 따른다.

## 백엔드 (Gradle)

| 구성요소 | 라이선스 | 비고 |
|---|---|---|
| Spring Boot · Spring Framework · Spring Data · Spring Kafka · Spring WebFlux | Apache-2.0 | |
| Jackson (databind, datatype-jsr310) | Apache-2.0 | |
| java-uuid-generator (com.fasterxml.uuid) | Apache-2.0 | UUID v7 |
| Gson | Apache-2.0 | |
| ZXing (core, javase) | Apache-2.0 | |
| networknt json-schema-validator | Apache-2.0 | Service Profile 스키마 검증 |
| OkHttp | Apache-2.0 | |
| Apache Commons (lang3, lang, codec, configuration) | Apache-2.0 | |
| Apache HttpClient 5 | Apache-2.0 | |
| Resilience4j | Apache-2.0 | |
| JJWT (api, impl, jackson) | Apache-2.0 | CAST 토큰 서명·검증 |
| Micrometer (prometheus registry, tracing bridge OTel) | Apache-2.0 | |
| OpenTelemetry (exporter-otlp, sdk-extension-autoconfigure) | Apache-2.0 | |
| Reactor Netty | Apache-2.0 | |
| Byte Buddy (agent) | Apache-2.0 | idem-agent |
| ShedLock | Apache-2.0 | idem-relay |
| Flyway (core, postgresql, mysql) | Apache-2.0 | |
| Redisson | Apache-2.0 | |
| springdoc-openapi | Apache-2.0 | |
| PostgreSQL JDBC Driver | BSD-2-Clause | |
| MariaDB Connector/J | LGPL-2.1 | `mariadb` 레거시 프로파일 전용 런타임 의존(동적 링크). S9 에서 제거 예정 |
| Javassist | MPL-1.1 / LGPL-2.1 / Apache-2.0 (삼중) | Apache-2.0 조건으로 사용 |
| Bouncy Castle (bcprov, bcpkix) | MIT 계열 (Bouncy Castle License) | |
| Lombok | MIT | 컴파일 시점 전용 |
| JUnit 5 | EPL-2.0 | 테스트 전용 |
| Mockito · AssertJ · Testcontainers · WireMock | MIT / Apache-2.0 | 테스트 전용 |

## 관리 콘솔 (idem-console-admin, npm) — S7 PR-2

| 구성요소 | 라이선스 |
|---|---|
| React · react-dom | MIT |
| Vite · @vitejs/plugin-react · esbuild · Rollup | MIT |
| TypeScript | Apache-2.0 |
| Vitest (테스트 전용) | MIT |

## KR 에디션 회원 포털 (editions/idem-kr-portal/frontend, yarn — 구 idem-console)

포털 프런트엔드는 **SigNoz 프런트엔드(MIT Expat)** 에서 파생했다. 원저작권 고지와 MIT 본문은 아래에 둔다.

| 구성요소 | 라이선스 |
|---|---|
| React · react-dom · react-router-dom · react-redux · react-query | MIT |
| Ant Design (antd, @ant-design/icons, @ant-design/colors) | MIT |
| @signozhq/design-tokens | MIT |
| @radix-ui/react-* · @dnd-kit/* · @visx/* · @xstate/react · @uiw/react-md-editor · @monaco-editor/react | MIT |
| @sentry/react · @sentry/webpack-plugin | MIT |
| @grafana/faro-web-sdk · @grafana/faro-web-tracing | Apache-2.0 |
| chart.js · d3 · dayjs · lodash-es · axios · i18next · dompurify · papaparse · posthog-js 등 | MIT / BSD / ISC |
| webpack · babel · jest 도구 체인 | MIT |

> 확인 사항: `@grafana/data` 는 AGPL-3.0 이며 소스에서 사용하지 않아 D0(공개 전 점검)에서 의존성에서 제거했다.

### SigNoz 고지 (MIT Expat)

Copyright (c) 2020-present SigNoz Inc.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## 런타임 구성요소 (이미지·서비스, 저장소 미포함)

| 구성요소 | 라이선스 | 비고 |
|---|---|---|
| Keycloak 24 | Apache-2.0 | 설치본 내부 구성요소 |
| PostgreSQL 16 | PostgreSQL License | |
| Redis 7.2 | BSD-3-Clause | 7.4 이후는 RSALv2/SSPL — 이미지 태그를 7.2 에 고정한 이유 |
| Apache Kafka · Confluent 이미지 | Apache-2.0 / Confluent Community License | 선택 의존 |
| Nginx | BSD-2-Clause | |
| k6 (부하·스모크 도구) | AGPL-3.0 | CI 도구로만 실행, 제품에 포함되지 않음 |
| HashiCorp Vault | BUSL-1.1 | 선택(KMS) |

## 벤더 SDK (저장소 미포함)

NICE 본인확인·OACX·행안부 Any-ID SDK 와 관련 자산은 각 사업자 계약 조건에 따라 별도로 공급받아 `~/.idem/vendor-libs` 에 둔다.
이 저장소는 SDK 를 재배포하지 않으며, 부재 시 해당 플러그인 패키지는 빌드에서 자동 제외된다.
