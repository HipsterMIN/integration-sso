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
 * 허용 목록({@link #ALLOWLIST})은 "이미 적용된 마이그레이션 이력" 처럼 지금 바꿀 수 없는 파일에 한정하고,
 * 항목마다 사유와 해소 단계를 적는다. 벤더 자산(NICE/OACX·AnyID)은 S5a·S5b 에서 플러그인·vendor-libs 로 나갔다.
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
                    "이미 적용된 PoC 시드 — 위와 동일"
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
        for (String suffix : KR_ALLOWLIST.keySet()) {
            assertThat(Files.exists(root.resolve(suffix)))
                    .withFailMessage("KR 허용 목록 경로가 없습니다 — 목록에서 제거하세요: %s", suffix)
                    .isTrue();
        }
    }

    // ── S5 벤더 가드 ────────────────────────────────────────────────────────

    /**
     * 코어 hub main 에 있으면 안 되는 벤더 SDK·API 토큰 ({@code docs/vendor-plugin-plan.md} P2·P3). NICE 본인확인·OACX 간편인증은
     * S5a 에서 {@code plugins/idem-plugin-nice-oacx} 로, AnyID 설치형 브로커·KMS·SDK·정적 번들은 S5b 에서
     * {@code plugins/idem-plugin-anyid} 로 옮겼으므로 hub 코어는 벤더 클래스·호스트를 직접 참조하지 않는다.
     */
    private static final List<String> VENDOR_FORBIDDEN = List.of(
            "NiceApiClient", "NicePhoneService", "NiceCryptoUtil", "NiceTokenStore", "NiceAuthSessionStore",
            "OacxClient", "OacxUtil", "OACX.", "niceid.co.kr", "import OACX",
            "AnyIdBrokerAdapter", "AnyIdController", "AnyIdKmsClient", "AnyIdSsobService", "AnyIdProperties",
            "kr.or.anyid", "AnyidCertRef", "anyid.dev", "anyid.go.kr", "kdist-api", "pid_api"
    );

    /** 경로 접미사 → 허용 사유 (벤더 가드). */
    private static final Map<String, String> VENDOR_ALLOWLIST = Map.of(
            "idem-hub/src/main/resources/application.yml",
                    "ido.auth.nice.base-url 기본값(플러그인 설정 키) — 플러그인 전용 설정 파일로 옮긴 뒤 제거",
            "idem-hub/src/main/resources/db/migration/V19__anyid_provider_config.sql",
                    "이미 적용된 provider_config 시드(broker_mode=anyid) — Flyway 이력상 수정 불가. 개명 5단계에서 KR 에디션 시드로 이동"
    );

    @Test
    @DisplayName("hub 코어 main 에 벤더 SDK·API 토큰이 없다 (레거시 프록시·적용된 마이그레이션 제외)")
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

    // ── S8-a KR 에디션 가드 ──────────────────────────────────────────────────
    // SMES 회원 개념(회원구분코드·기업회원·개인/기업 회원 ID·회원전환·기관 회원조회)은 editions/idem-kr-* 에만 있다.
    // 코어 main 에 이 토큰이 다시 들어오면 실패한다. (CI/DI 스킴 자체는 SubjectScheme 의 한 값이라 금지하지 않는다)
    private static final List<String> KR_FORBIDDEN = List.of(
            "mbrDvsnCd", "MemberDivision", "bizno", "cmpMbrId", "indvlMbrId", "indvlMbrNm", "entMbrNo",
            "biz_member", "BizMember", "bizRegNo", "MemberLookupService", "MemberLookupController",
            "ConversionInit", "ConversionSession", "checkNiceCi", "CiCheckRequest", "CiTokenExchange",
            "IntegrationAuthClient", "ImApiOutPort", "hub.kr.", "registry.kr.", "\"A101\"", "\"A102\""
    );

    /** KR 가드 허용 목록 — 남은 SMES 흔적과 그 이유. 줄어들어야지 늘어나면 안 된다. */
    private static final Map<String, String> KR_ALLOWLIST = Map.of(
            "idem-hub/src/main/java/io/github/hipstermin/idem/hub/qim/sp/",
                    "SMES SP 수신기(기관 회원 ID 매핑 mbrUuid/entMbrNo, BIZ/PERSONAL) — WebhookDispatcherService·SloServiceImpl 가 쓰는 코어 결합. S8-b 에서 범용 기관회원매핑으로 바꾸거나 KR 에디션으로 이동",
            "idem-hub/src/main/resources/db/migration/",
                    "이미 적용된 hub Flyway 이력(V4 qim_sp_receiver, V7·V9 주석) — 개명 5단계(DB 재구축)에서 정리",
            "idem-registry/src/main/resources/db/migration/mariadb/",
                    "D1 이전 MariaDB 이력(biz_member V6 등) — mariadb 프로파일 호환용, S9 에서 제거"
    );

    @Test
    @DisplayName("코어 main(hub·registry·common·gate·authz) 에 SMES 회원 개념 토큰이 없다 — KR 에디션 모듈(editions/) 전용")
    void coreContainsNoKrEditionConcepts() throws IOException {
        Path root = repositoryRoot();
        List<String> violations = new ArrayList<>();
        for (String module : List.of("idem-common", "idem-gate", "idem-registry", "idem-hub", "idem-authz")) {
            Path main = root.resolve(module).resolve("src/main");
            if (!Files.isDirectory(main)) continue;
            try (Stream<Path> files = Files.walk(main)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    if (!EXTENSIONS.contains(extensionOf(file))) continue;
                    String rel = root.relativize(file).toString().replace('\\', '/');
                    if (isAllowlisted(rel) || KR_ALLOWLIST.keySet().stream().anyMatch(rel::startsWith)) continue;
                    scan(file, rel, KR_FORBIDDEN, violations);
                }
            }
        }
        assertThat(violations)
                .withFailMessage("코어에 KR 에디션(SMES) 개념이 있습니다. editions/idem-kr-hub 또는 idem-kr-registry 로 옮기세요:\n  "
                        + String.join("\n  ", violations))
                .isEmpty();
    }

    @Test
    @DisplayName("코어 hub·registry 는 KR 에디션 모듈을 빌드 의존하지 않는다 (에디션 → 코어 단방향)")
    void coreDoesNotDependOnKrEditionModules() throws IOException {
        Path root = repositoryRoot();
        for (String module : List.of("idem-hub", "idem-registry", "idem-common", "idem-gate", "idem-authz")) {
            String build = Files.readString(root.resolve(module).resolve("build.gradle.kts"));
            assertThat(build).withFailMessage("%s/build.gradle.kts 가 KR 에디션 모듈을 의존합니다", module)
                    .doesNotContain(":idem-kr-hub").doesNotContain(":idem-kr-registry");
        }
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
