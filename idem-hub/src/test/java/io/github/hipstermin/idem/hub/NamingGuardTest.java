package io.github.hipstermin.idem.hub;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 개명 4b 가드 (S9) — 코드·설정·설치본은 새 런타임 식별자만 쓴다. 구 이름은 {@code LegacyNames}(호환 계층) 와 적용된 마이그레이션에만 남는다.
 * 대응표: {@code docs/naming.md} §3.
 */
@DisplayName("개명 가드 — 구 런타임 식별자 금지 (설정 키·환경변수·Redis 접두·앱 이름)")
class NamingGuardTest {

    private static final Path ROOT = findRoot();

    /** 구 설정 키·환경변수·Redis 접두·앱/호출자 이름 토큰 */
    private static final List<Pattern> FORBIDDEN = List.of(
            Pattern.compile("\\$\\{(ido|qsign|qim|authz|batch|agency-stub)\\."),          // ${ido.x}
            Pattern.compile("\"(ido|qsign|qim|authz|batch|agency-stub)\\.[a-z][a-z0-9.-]*\""), // "ido.x" (스키마.테이블은 밑줄이 있어 제외)
            Pattern.compile("^(ido|qsign|qim|authz|batch|agency-stub):\\s*$", Pattern.MULTILINE), // yml 루트
            Pattern.compile("\\$\\{(IDO|QSIGN|QIM|QAUTHZ)_[A-Z0-9_]+"),                  // ${IDEM_HUB_X} 자리표시자 (Java 상수명 IDO_TICKET_* 는 오류 코드라 제외)
            Pattern.compile("\"(ido|qsign):[a-z]"),                                        // Redis 접두
            Pattern.compile("\"fe:(session|user-sessions|idp-sid|idp-sub)"),
            Pattern.compile("name: (q-sign|q-im|q-authz|ido|outbox-relay-batch|agency-stub)\\s*$", Pattern.MULTILINE),
            Pattern.compile("\"(q-sign|q-im|q-authz|onepass-ido)\""),
            // 5단계 (S9 PR-2): 스키마·DB·Keycloak realm/client·CAST 폼 필드
            Pattern.compile("schema = \"(ido|qsign|qim|authz)\"|currentSchema=(ido|qsign|qim|authz)\\b|\\.schemas\\(\"(ido|qsign|qim|authz)\"\\)"),
            Pattern.compile("^\\s*(default_schema|schemas|default-schema):\\s*(ido|qsign|qim|authz)\\s*$", Pattern.MULTILINE),
            Pattern.compile("\\b(ido|qsign|qim|authz)\\.(outbox|audit_log|agency_meta|auth_result|tenant|shedlock|flyway_schema_history)\\b"),
            Pattern.compile("/realms/onepass|\"onepass\"|KEYCLOAK_REALM[:=]\\s*onepass|onepass[._](kms|outbox)[._]|\"q-sign-client\"|\"ido-client\"|onepass_sso|ido-(keycloak|nonoidc|adapter)"),
            Pattern.compile("\\b(DB_NAME|DB_USERNAME|POSTGRES_DB|POSTGRES_USER)[:=] ?onepass\\b"),
            // 1.0.1 (3차 점검 LOW): FE·스크립트 잔재 — 구 헤더·Keycloak realm/client 이름
            Pattern.compile("X-IDO-API-Key|\\bIDO_(API_KEY|API_ENDPOINT|BASE_URL)\\b|'ucube-qsign'|'onepassCli'|\\bQSIGN_(REALM|CLIENT_ID)\\b")
    );

    /** Java 밖(yml·sh·compose·Helm·CI)에서는 환경변수 이름 자체를 금지한다 */
    private static final Pattern FORBIDDEN_ENV_NAME = Pattern.compile("\\b(IDO|QSIGN|QIM|QAUTHZ|AUTHZ|BATCH)_[A-Z0-9_]+|\\bIDEM_ADMIN_(BOOTSTRAP|SECRET_KEY|ALLOW_DERIVED|SESSION_|COOKIE_|MFA_)");

    private static final List<String> SCAN_DIRS = List.of(
            "idem-common/src/main", "idem-hub/src/main", "idem-gate/src/main", "idem-registry/src/main", "idem-authz/src/main",
            "idem-relay/src/main", "idem-tenant-sample/src/main", "editions/idem-kr-hub/src/main", "editions/idem-kr-registry/src/main",
            "plugins/idem-plugin-mock-auth/src/main", "plugins/idem-plugin-nice-oacx/src/main", "plugins/idem-plugin-anyid/src/main",
            "infra/docker", "infra/helm", "scripts", ".github/workflows", "idem-hub/src/testFixtures",
            // 1.0.1 (3차 점검 LOW): 사각이던 FE 소스·Dockerfile 도 본다
            "idem-console-admin/src", "editions/idem-kr-portal/frontend/src");

    /** 모듈 루트의 Dockerfile* (SCAN_DIRS 밖) */
    private static final List<String> DOCKERFILE_GLOBS = List.of("idem-gate", "idem-hub", "idem-registry", "idem-authz", "idem-relay",
            "idem-tenant-sample", "idem-console-admin", "editions/idem-kr-portal");

    private static final Set<String> ALLOW_FILES = Set.of(
            "idem-common/src/main/java/io/github/hipstermin/idem/common/naming/LegacyNames.java",
            "idem-common/src/main/java/io/github/hipstermin/idem/common/naming/LegacyNamesEnvironmentPostProcessor.java");
    /** 적용된 마이그레이션은 역사 — 단 스키마 접두는 5단계에서 새 이름으로 고쳤다(체크섬은 LegacySchemaRename 이 repair) */
    private static final Set<String> ALLOW_PATH_PARTS = Set.of("/db/migration/", "/node_modules/", "/build/", "/.git/");

    @Test
    @DisplayName("코어·에디션·플러그인 main, 설치본(compose·env·Helm), 스크립트, CI 에 구 이름이 없다")
    void noLegacyRuntimeIdentifiers() throws IOException {
        List<String> violations = new ArrayList<>();
        List<Path> extra = new ArrayList<>();
        for (String m : DOCKERFILE_GLOBS) {
            Path d = ROOT.resolve(m);
            if (!Files.isDirectory(d)) continue;
            try (Stream<Path> l = Files.list(d)) { l.filter(x -> x.getFileName().toString().startsWith("Dockerfile")).forEach(extra::add); }
        }
        for (String dir : SCAN_DIRS) {
            Path base = ROOT.resolve(dir);
            if (!Files.exists(base)) continue;
            try (Stream<Path> files = Files.walk(base)) {
                for (Path f : (Iterable<Path>) Stream.concat(files.filter(Files::isRegularFile), dir.equals(SCAN_DIRS.get(0)) ? extra.stream() : Stream.<Path>empty())::iterator) {
                    String rel = ROOT.relativize(f).toString().replace('\\', '/');
                    if (ALLOW_FILES.contains(rel) || ALLOW_PATH_PARTS.stream().anyMatch(("/" + rel)::contains)) continue;
                    String name = f.getFileName().toString();
                    if (!(name.endsWith(".java") || name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".properties")
                            || name.endsWith(".sh") || name.endsWith(".json") || name.endsWith(".example") || name.endsWith(".tpl")
                            || name.endsWith(".lua") || name.endsWith(".factories")
                            || name.endsWith(".ts") || name.endsWith(".tsx") || name.endsWith(".py") || name.startsWith("Dockerfile"))) continue;
                    String text = Files.readString(f, StandardCharsets.UTF_8);
                    List<Pattern> patterns = new ArrayList<>(FORBIDDEN);
                    if (!name.endsWith(".java")) patterns.add(FORBIDDEN_ENV_NAME);
                    for (Pattern p : patterns) {
                        var m = p.matcher(text);
                        int n = 0;
                        while (m.find() && n < 3) {
                                            if (m.group().startsWith("authz:") && rel.startsWith("infra/helm/")) continue;           // Helm values 의 모듈 키
                            int line = (int) text.chars().limit(m.start()).filter(c -> c == '\n').count() + 1;
                            violations.add(rel + ":" + line + "  " + m.group());
                            n++;
                        }
                    }
                }
            }
        }
        assertThat(violations).as("구 런타임 식별자는 새 이름으로 (docs/naming.md §3). 위반:\n" + String.join("\n", violations)).isEmpty();
    }

    private static Path findRoot() {
        Path p = Paths.get("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("settings.gradle.kts"))) p = p.getParent();
        if (p == null) throw new IllegalStateException("settings.gradle.kts 를 찾지 못했다");
        return p;
    }
}
