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
            Pattern.compile("\"(q-sign|q-im|q-authz|onepass-ido)\"")
    );

    /** Java 밖(yml·sh·compose·Helm·CI)에서는 환경변수 이름 자체를 금지한다 */
    private static final Pattern FORBIDDEN_ENV_NAME = Pattern.compile("\\b(IDO|QSIGN|QIM|QAUTHZ)_[A-Z0-9_]+|\\bIDEM_ADMIN_(BOOTSTRAP|SECRET_KEY|ALLOW_DERIVED|SESSION_|COOKIE_|MFA_)");

    private static final List<String> SCAN_DIRS = List.of(
            "idem-common/src/main", "idem-hub/src/main", "idem-gate/src/main", "idem-registry/src/main", "idem-authz/src/main",
            "idem-relay/src/main", "idem-tenant-sample/src/main", "editions/idem-kr-hub/src/main", "editions/idem-kr-registry/src/main",
            "plugins/idem-plugin-mock-auth/src/main", "plugins/idem-plugin-nice-oacx/src/main", "plugins/idem-plugin-anyid/src/main",
            "infra/docker", "infra/helm", "scripts", ".github/workflows", "idem-hub/src/testFixtures");

    private static final Set<String> ALLOW_FILES = Set.of(
            "idem-common/src/main/java/io/github/hipstermin/idem/common/naming/LegacyNames.java",
            "idem-common/src/main/java/io/github/hipstermin/idem/common/naming/LegacyNamesEnvironmentPostProcessor.java");
    private static final Set<String> ALLOW_PATH_PARTS = Set.of("/db/migration/", "/node_modules/", "/build/", "/.git/");

    @Test
    @DisplayName("코어·에디션·플러그인 main, 설치본(compose·env·Helm), 스크립트, CI 에 구 이름이 없다")
    void noLegacyRuntimeIdentifiers() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String dir : SCAN_DIRS) {
            Path base = ROOT.resolve(dir);
            if (!Files.exists(base)) continue;
            try (Stream<Path> files = Files.walk(base)) {
                for (Path f : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                    String rel = ROOT.relativize(f).toString().replace('\\', '/');
                    if (ALLOW_FILES.contains(rel) || ALLOW_PATH_PARTS.stream().anyMatch(("/" + rel)::contains)) continue;
                    String name = f.getFileName().toString();
                    if (!(name.endsWith(".java") || name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".properties")
                            || name.endsWith(".sh") || name.endsWith(".json") || name.endsWith(".example") || name.endsWith(".tpl")
                            || name.endsWith(".lua") || name.endsWith(".factories"))) continue;
                    String text = Files.readString(f, StandardCharsets.UTF_8);
                    List<Pattern> patterns = new ArrayList<>(FORBIDDEN);
                    if (!name.endsWith(".java")) patterns.add(FORBIDDEN_ENV_NAME);
                    for (Pattern p : patterns) {
                        var m = p.matcher(text);
                        int n = 0;
                        while (m.find() && n < 3) {
                            if (m.group().matches("\"(ido|qsign|qim|authz)\\.(outbox|shedlock|o)\"")) continue; // 스키마.테이블 (5단계)
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
