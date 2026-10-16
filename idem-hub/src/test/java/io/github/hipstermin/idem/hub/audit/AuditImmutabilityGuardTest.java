package io.github.hipstermin.idem.hub.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hipstermin.idem.hub.admin.audit.AuditQueryController;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * 시험 항목 G-2 "감사 불변" (G2-13, 2026-10-16 — 설계 → 자동).
 *
 * <p>감사 행({@code idem_hub.audit_log})의 내용은 쓰고 난 뒤 바뀌지 않는다: 제품 코드(코어·KR 에디션 main)에 감사 행을 지우거나
 * 내용을 고치는 SQL·JPA 가 없어야 한다. 허용하는 유일한 갱신은 Kafka 발행 표식({@code kafka_published·kafka_published_at}) 이다
 * ({@code AuditLogPublisher}). 관리 API 쪽은 둘이 본다 — 컨트롤러에 GET 매핑만 있다(이 클래스), 쓰기는 인가 매트릭스가 모든 역할에 403
 * ({@code AdminAuthorizationTest.auditIsReadOnlyForEveryRole}, 끝-끝 {@code AdminAuthIntegrationTest.auditEndpointsAreReadOnly}).
 */
@DisplayName("감사 불변 — audit_log 를 고치거나 지우는 코드가 없다 (G-2)")
class AuditImmutabilityGuardTest {

    private static final Path ROOT = findRoot();
    private static final List<String> SCAN_DIRS = List.of("idem-hub/src/main", "editions/idem-kr-hub/src/main", "idem-common/src/main");
    /** 감사 행 삭제·갱신으로 볼 SQL — 대소문자 무시, 스키마 접두 유무 모두 */
    private static final Pattern MUTATION = Pattern.compile(
            "(?i)\\b(DELETE\\s+FROM|UPDATE|TRUNCATE(\\s+TABLE)?|ALTER\\s+TABLE)\\s+(idem_hub\\.)?audit_log\\b");
    /** 유일하게 허용하는 갱신: Kafka 발행 표식 */
    private static final Pattern ALLOWED_UPDATE = Pattern.compile(
            "(?is)UPDATE\\s+(idem_hub\\.)?audit_log\\s+SET\\s+kafka_published\\s*=\\s*TRUE\\s*,\\s*kafka_published_at\\s*=\\s*NOW\\(\\)\\s+WHERE\\s+audit_id\\s*=\\s*\\?");

    @Test
    @DisplayName("코어·KR 에디션 main 소스에 audit_log DELETE/UPDATE/TRUNCATE 가 없다(Kafka 발행 표식 갱신만 허용)")
    void noAuditLogMutation() throws IOException {
        List<String> violations = new ArrayList<>();
        int scanned = 0;
        for (String dir : SCAN_DIRS) {
            Path base = ROOT.resolve(dir);
            if (!Files.exists(base)) continue;
            try (Stream<Path> files = Files.walk(base)) {
                for (Path f : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                    String name = f.getFileName().toString();
                    if (!(name.endsWith(".java") || name.endsWith(".xml") || name.endsWith(".yml") || name.endsWith(".properties"))) continue;
                    String rel = ROOT.relativize(f).toString().replace('\\', '/');
                    if (rel.contains("/db/migration/")) continue;   // 스키마 이력(열 추가 등)은 내용 변경이 아니다
                    String text = Files.readString(f, StandardCharsets.UTF_8);
                    scanned++;
                    Matcher m = MUTATION.matcher(text);
                    while (m.find()) {
                        String tail = text.substring(m.start(), Math.min(text.length(), m.start() + 220));
                        if (ALLOWED_UPDATE.matcher(tail).find() && rel.endsWith("/audit/AuditLogPublisher.java")) continue;
                        int line = (int) text.chars().limit(m.start()).filter(c -> c == '\n').count() + 1;
                        violations.add(rel + ":" + line + "  " + m.group());
                    }
                }
            }
        }
        assertThat(scanned).as("스캔한 파일 수").isGreaterThan(100);
        assertThat(violations).as("감사 행은 불변이다 — 지우거나 고치는 코드가 생겼다:\n" + String.join("\n", violations)).isEmpty();
    }

    @Test
    @DisplayName("감사 JPA 저장소에 삭제·갱신 메서드(@Modifying, delete*)가 없다")
    void auditRepositoriesHaveNoDeleteOrModify() throws IOException {
        List<String> violations = new ArrayList<>();
        Path base = ROOT.resolve("idem-hub/src/main/java");
        try (Stream<Path> files = Files.walk(base)) {
            for (Path f : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                String name = f.getFileName().toString();
                if (!(name.contains("Audit") && name.contains("Repository") && name.endsWith(".java"))) continue;
                String text = Files.readString(f, StandardCharsets.UTF_8);
                if (text.contains("@Modifying") || text.matches("(?s).*\\b(delete|remove)[A-Za-z]*\\s*\\(.*")) {
                    violations.add(ROOT.relativize(f).toString());
                }
            }
        }
        assertThat(violations).as("감사 저장소에 삭제·갱신 메서드가 생겼다: " + violations).isEmpty();
    }

    @Test
    @DisplayName("감사 조회 컨트롤러(/api/v1/admin/audit)에는 GET 매핑만 있다")
    void auditControllerHasOnlyGetMappings() {
        List<String> violations = new ArrayList<>();
        int gets = 0;
        for (Method m : AuditQueryController.class.getDeclaredMethods()) {
            if (m.isAnnotationPresent(PostMapping.class) || m.isAnnotationPresent(PutMapping.class)
                    || m.isAnnotationPresent(DeleteMapping.class) || m.isAnnotationPresent(PatchMapping.class)) {
                violations.add(m.getName());
            }
            RequestMapping rm = m.getAnnotation(RequestMapping.class);
            if (rm != null) {
                for (RequestMethod rmm : rm.method()) {
                    if (rmm != RequestMethod.GET && rmm != RequestMethod.HEAD && rmm != RequestMethod.OPTIONS) violations.add(m.getName() + ":" + rmm);
                }
                if (rm.method().length == 0) violations.add(m.getName() + ":RequestMapping(메서드 미지정 = 전부)");
            }
            if (m.isAnnotationPresent(GetMapping.class)) gets++;
        }
        assertThat(gets).as("조회 매핑").isGreaterThanOrEqualTo(1);
        assertThat(violations).as("감사 조회 컨트롤러에 쓰기 매핑이 생겼다: " + violations).isEmpty();
    }

    private static Path findRoot() {
        Path p = Paths.get("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("settings.gradle.kts"))) p = p.getParent();
        if (p == null) throw new IllegalStateException("settings.gradle.kts 를 찾지 못했다");
        return p;
    }
}
