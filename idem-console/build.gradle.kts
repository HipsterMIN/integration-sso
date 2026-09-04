// ══════════════════════════════════════════════════════════════════════════════
// idem-console — 순수 React SPA 모듈 (v2.0.0, 2026-05-10 통합)
//
// Spring Boot BFF 책임은 idem-hub 모듈로 이관.
// 이 모듈은 React(TypeScript) + Webpack5 + Ant Design 프론트엔드만 담당.
//
// 개발 (Option B):  ./gradlew :idem-console:frontendDev
//   → webpack-dev-server port 3301, proxy:
//     /api/ext/** → Q-IM (EXT_API_ENDPOINT or https://onepass-dev.smes.go.kr/im)
//     /api/**     → idem-hub:8083 (BE_API_TARGET)
//
// 빌드 (Option A):  ./gradlew :idem-console:build
//   → frontend/dist/ 산출물. Nginx 혹은 idem-hub 정적 리소스로 서빙
//
// 소스 구성:
//   - src/pages/      : Login, ConversionSteps, RegisterSteps, Mypage, OacxTest
//   - src/api/        : beInstance (idem-hub:8083), extInstance (Q-IM)
//   - src/providers/  : AppProvider, ConversionProvider, RegisterProvider
//   - src/hooks/      : useEzAuth, useNicePhoneAuth, usePersonalEasyAuth 등
// ══════════════════════════════════════════════════════════════════════════════
plugins {
    id("com.github.node-gradle.node") version "7.1.0"
}

// ── Node / Yarn 버전 고정 ──────────────────────────────────────────────────
// [출시 NO-GO 조치 — 2026-05-24] Node 20.19.0 으로 상향
// 사유: yarn install 시 transitive 의존 `sass@1.100.0` 이 Node `>=20.19.0` 요구.
// 기존 20.14.0 은 engine mismatch 로 :idem-console:yarnInstall 실패.
// 향후 sass major 업데이트 시 다시 확인 필요.
node {
    version        = "20.19.0"
    yarnVersion    = "1.22.22"
    download       = true
    nodeProjectDir = file("${projectDir}/frontend")
}

// ── 의존성 없음: 순수 프론트엔드 모듈 ────────────────────────────────────
// Spring Boot, Kafka, Redis 의존성은 모두 제거됨.
// BFF 기능은 idem-hub 모듈(port 8083)에서 제공.

// ── 태스크 ────────────────────────────────────────────────────────────────

/** frontend/node_modules 설치 */
val yarnInstall by tasks.registering(com.github.gradle.node.yarn.task.YarnTask::class) {
    description = "Install React frontend dependencies"
    group       = "frontend"
    // --frozen-lockfile 를 사용하지 않음: yarn.lock 이 없거나 비어있는 초기 환경 대응
    args        = listOf("install")
    workingDir  = file("${projectDir}/frontend")
    inputs.files(file("frontend/package.json"))
    outputs.dir("frontend/node_modules")
}

/** yarn build:prod — Webpack 프로덕션 빌드 */
val yarnBuild by tasks.registering(com.github.gradle.node.yarn.task.YarnTask::class) {
    description = "Build React frontend (production)"
    group       = "frontend"
    dependsOn(yarnInstall)
    args        = listOf("build:prod")
    workingDir  = file("${projectDir}/frontend")
    inputs.dir("frontend/src")
    inputs.files(file("frontend/package.json"), file("frontend/webpack.config.js"))
    outputs.dir("frontend/dist")
}

/** build 태스크 = yarnBuild */
tasks.named("build") {
    dependsOn(yarnBuild)
}

/** Option B: React 개발서버 기동 (port 3301, /api/** → idem-hub:8083) */
tasks.register<com.github.gradle.node.yarn.task.YarnTask>("frontendDev") {
    description = "Start React dev server on port 3301 (proxy /api/ext → Q-IM, /api → idem-hub:8083)"
    group       = "frontend"
    dependsOn(yarnInstall)
    args        = listOf("dev")
    workingDir  = file("${projectDir}/frontend")
}

/** ESLint */
tasks.register<com.github.gradle.node.yarn.task.YarnTask>("lint") {
    description = "Run ESLint on frontend sources"
    group       = "frontend"
    dependsOn(yarnInstall)
    args        = listOf("lint")
    workingDir  = file("${projectDir}/frontend")
}

/** Jest */
tasks.register<com.github.gradle.node.yarn.task.YarnTask>("test") {
    description = "Run Jest unit tests"
    group       = "frontend"
    dependsOn(yarnInstall)
    args        = listOf("test")
    workingDir  = file("${projectDir}/frontend")
}
