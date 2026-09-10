package io.github.hipstermin.idem.hub;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S1 범용화 가드 — 코어 모듈의 main 소스·리소스에 특정 고객(운영기관) 고유값이 다시 들어오는 것을 막는다
 * ({@code docs/generalization-plan.md} §1.2 C1).
 *
 * <p>고객 고유값은 설치 시 설정(환경변수·Helm values·Service Profile)으로 주입해야 하며, 코어 코드·기본값·주석에도 남기지 않는다.
 * 허용 목록({@link #ALLOWLIST})은 "이미 적용된 마이그레이션 이력" 처럼 지금 바꿀 수 없는 파일과, 뒤 단계(S5·S8)에서
 * 에디션 플러그인으로 옮길 벤더 자산에 한정하고, 항목마다 사유와 해소 단계를 적는다.
 */
@DisplayName("범용화 가드 — 코어에 고객 고유값 금지")
class GeneralizationGuardTest {

    /** 코어 모듈 (플러그인·샘플·SDK·콘솔 프런트는 대상 아님). */
    private static final List<String> CORE_MODULES = List.of(
            "idem-common", "idem-gate", "idem-registry", "idem-hub", "idem-authz", "idem-relay");

    /** 검사 대상 확장자. */
    private static final Set<String> EXTENSIONS = Set.of("java", "yml", "yaml", "properties", "sql", "json", "xml", "kts");

    /** 코어에 있으면 안 되는 고객 고유 토큰. */
    private static final List<String> FORBIDDEN = List.of(
            "smes.go.kr",            // 운영기관 도메인
            "1000001157",            // AnyID 서비스 번호(기관 식별자)
            "중소벤처",               // 운영기관·서비스명
            "기업마당",
            "중기원패스",
            "중소기업기술정보진흥원"
    );

    /** 경로 접미사 → 허용 사유. 새 항목을 넣을 때는 사유와 해소 단계를 반드시 적는다. */
    private static final Map<String, String> ALLOWLIST = Map.of(
            "idem-hub/src/main/resources/db/migration/V13__seed_agency_pattern_scenarios.sql",
                    "이미 적용된 PoC 기관 시드 — Flyway 이력상 수정 불가. 개명 5단계(DB 재구축)에서 KR 에디션 시드로 이동",
            "idem-hub/src/main/resources/db/migration/V8__seed_agency_api_key_and_fix_webhook.sql",
                    "이미 적용된 PoC 시드 — 위와 동일",
            "idem-hub/src/main/resources/sso-adaptor-conf-local.properties",
                    "AnyID 벤더 SDK 설정 — S5 에서 idem-plugin-anyid 로 이동",
            "idem-hub/src/main/resources/config/anyid/",
                    "AnyID 벤더 SDK 설정 파일 — S5 에서 idem-plugin-anyid 로 이동",
            "idem-hub/src/main/resources/static/",
                    "AnyID 벤더 프런트 번들 — S5 에서 플러그인으로 이동 (open-source-readiness B3)"
    );

    @Test
    @DisplayName("코어 모듈 main 소스·리소스에 고객 고유 토큰이 없다 (허용 목록 제외)")
    void coreModulesContainNoCustomerSpecificTokens() throws IOException {
        Path root = repositoryRoot();
        List<String> violations = new ArrayList<>();

        for (String module : CORE_MODULES) {
            Path main = root.resolve(module).resolve("src/main");
            if (!Files.isDirectory(main)) continue;
            try (Stream<Path> files = Files.walk(main)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    if (!EXTENSIONS.contains(extensionOf(file))) continue;
                    String rel = root.relativize(file).toString().replace('\\', '/');
                    if (isAllowlisted(rel)) continue;
                    scan(file, rel, FORBIDDEN, violations);
                }
            }
        }

        assertThat(violations)
                .withFailMessage("코어 모듈에 고객 고유값이 있습니다. 설정으로 외부화하거나(설치 시 주입) 에디션 플러그인으로 옮기세요:\n  "
                        + String.join("\n  ", violations))
                .isEmpty();
    }

    @Test
    @DisplayName("허용 목록의 경로는 실제로 존재한다 (지워진 파일이 목록에 남지 않도록)")
    void allowlistEntriesExist() {
        Path root = repositoryRoot();
        for (String suffix : ALLOWLIST.keySet()) {
            assertThat(Files.exists(root.resolve(suffix)))
                    .withFailMessage("허용 목록 경로가 없습니다 — 목록에서 제거하세요: %s", suffix)
                    .isTrue();
        }
        for (String suffix : VENDOR_ALLOWLIST.keySet()) {
            assertThat(Files.exists(root.resolve(suffix)))
                    .withFailMessage("벤더 허용 목록 경로가 없습니다 — 목록에서 제거하세요: %s", suffix)
                    .isTrue();
        }
    }

    // ── S5 벤더 가드 ────────────────────────────────────────────────────────

    /**
     * 코어 hub main 에 있으면 안 되는 벤더 SDK·API 토큰 ({@code docs/vendor-plugin-plan.md} P2). NICE 본인확인·OACX 간편인증은
     * S5a 에서 {@code plugins/idem-plugin-nice-oacx} 로 옮겼으므로 hub 코어는 벤더 클래스·호스트를 직접 참조하지 않는다.
     */
    private static final List<String> VENDOR_FORBIDDEN = List.of(
            "NiceApiClient", "NicePhoneService", "NiceCryptoUtil", "NiceTokenStore", "NiceAuthSessionStore",
            "OacxClient", "OacxUtil", "OACX.", "niceid.co.kr", "import OACX"
    );

    /** 경로 접미사 → 허용 사유 (벤더 가드). */
    private static final Map<String, String> VENDOR_ALLOWLIST = Map.of(
            "idem-hub/src/main/java/io/github/hipstermin/idem/hub/auth/legacy/",
                    "구 벤더 엔드포인트(/api/v1/auth/nice|oacx/*) 호환 프록시 — 콘솔 훅·k6 가 SPI 엔드포인트로 옮겨간 뒤 제거",
            "idem-hub/src/main/java/io/github/hipstermin/idem/hub/broker/anyid/",
                    "AnyID 브로커 — S5b 에서 idem-plugin-anyid 로 이동",
            "idem-hub/src/main/resources/application.yml",
                    "ido.auth.nice.base-url 기본값(플러그인 설정 키) — 플러그인 전용 설정 파일로 옮긴 뒤 제거",
            "idem-hub/src/main/resources/sso-adaptor-conf-local.properties",
                    "AnyID 벤더 SDK 설정 — S5b 에서 idem-plugin-anyid 로 이동",
            "idem-hub/src/main/resources/config/anyid/",
                    "AnyID 벤더 SDK 설정 파일 — S5b",
            "idem-hub/src/main/resources/static/",
                    "AnyID 벤더 프런트 번들 — S5b"
    );

    @Test
    @DisplayName("hub 코어 main 에 벤더 SDK·API 토큰이 없다 (레거시 프록시·AnyID 허용 목록 제외)")
    void hubCoreContainsNoVendorTokens() throws IOException {
        Path root = repositoryRoot();
        Path main = root.resolve("idem-hub/src/main");
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(main)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                if (!EXTENSIONS.contains(extensionOf(file))) continue;
                String rel = root.relativize(file).toString().replace('\\', '/');
                if (VENDOR_ALLOWLIST.keySet().stream().anyMatch(rel::startsWith)) continue;
                scan(file, rel, VENDOR_FORBIDDEN, violations);
            }
        }
        assertThat(violations)
                .withFailMessage("hub 코어에 벤더 토큰이 있습니다. 벤더 코드는 plugins/ 의 IdentityVerificationProvider 플러그인으로 옮기세요:\n  "
                        + String.join("\n  ", violations))
                .isEmpty();
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static void scan(Path file, String rel, List<String> tokens, List<String> violations)
            throws IOException {
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (MalformedInputException e) {
            return; // 바이너리·비UTF-8 파일은 대상 아님
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            for (String token : tokens) {
                if (line.contains(token)) {
                    violations.add(rel + ":" + (i + 1) + "  [" + token + "]  " + line.trim());
                }
            }
        }
    }

    private static boolean isAllowlisted(String rel) {
        return ALLOWLIST.keySet().stream().anyMatch(rel::startsWith);
    }

    private static String extensionOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    /** Gradle 테스트 JVM 의 작업 디렉터리는 모듈 디렉터리이므로 settings.gradle.kts 가 있는 곳까지 올라간다. */
    private static Path repositoryRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("settings.gradle.kts"))) {
            dir = dir.getParent();
        }
        if (dir == null) throw new IllegalStateException("settings.gradle.kts 를 찾을 수 없습니다");
        return dir;
    }
}
