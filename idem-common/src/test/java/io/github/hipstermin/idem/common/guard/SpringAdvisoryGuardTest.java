package io.github.hipstermin.idem.common.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * G2-5 취약점 점검 1회차 가드 — OSS 수정판이 없는 Spring Framework 6.2.x 권고 세 건의 전제 조건이 제품 코드에 없음을 묶어 둔다
 * ({@code docs/certification/vulnerability-review-2026-10.md} §4, 루트 {@code .trivyignore}).
 *
 * <ul>
 *   <li>CVE-2026-47884 — {@code XsltView} 와 {@code "/**"} 뷰 매핑이 있어야 한다.</li>
 *   <li>CVE-2026-47890 — 뷰 조각(fragments)을 Server-Sent Events 로 보내야 한다.</li>
 *   <li>CVE-2026-47892 — WebFlux 함수형 엔드포인트({@code RouterFunction})를 DispatcherServlet 과 함께 써야 한다.</li>
 *   <li>CVE-2026-47874(Reactor Netty, OSS 수정판 1.3.7 = Spring Boot 4) — Reactor Netty 를 <b>HTTP 서버</b>로 써야 한다.
 *       우리는 {@code WebClient}(발신)만 쓰고 서버는 Tomcat 이다(빌드 파일에 {@code spring-boot-starter-webflux} 없음).</li>
 *   <li>Spring Framework 6.2.x 2026-08-20 일괄 14건(CVE-2026-47883·47885~47889·47891·47893·59280~59283·59313·59314, 6.2.20 은 Enterprise 전용)
 *       — UrlHandlerFilter·WebFlux 업로드·신뢰할 수 없는 SpEL·UrlFileNameViewController·RSocket·Jetty·Jaxb2/Aalto·WebSocket 핸드셰이크·FreeMarker·
 *       EscapedErrors·자가 확장 List 바인딩·SpEL 컴파일러·함수형 SSE·ContentDisposition 중 하나를 써야 한다. 하나도 쓰지 않는다.</li>
 * </ul>
 *
 * <p>이 토큰 중 하나라도 main 소스에 들어오면 억제 근거가 사라지므로, 그때는 {@code .trivyignore} 의 해당 줄을 지우고
 * 수정판(Spring Framework 7 / Spring Boot 4)으로 올리거나 다른 완화책을 문서에 적는다. 테스트 소스·플러그인·SDK 는 대상 밖
 * (이미지에 실리는 main 코드만 본다).
 */
@DisplayName("Spring 권고 가드 — 억제한 CVE 의 전제 조건이 제품 코드에 없다")
class SpringAdvisoryGuardTest {

    private static final List<String> MODULES = List.of(
            "idem-common", "idem-gate", "idem-registry", "idem-hub", "idem-authz", "idem-relay", "idem-tenant-sample",
            "editions/idem-kr-hub", "editions/idem-kr-registry", "editions/idem-kr-portal");

    /** 토큰 → 묶인 CVE. import 와 FQN 인라인 사용 모두 잡는다. */
    private static final List<String[]> FORBIDDEN = List.of(
            new String[] {"XsltView", "CVE-2026-47884"},
            new String[] {"FragmentsRendering", "CVE-2026-47890"},
            new String[] {"SseEmitter", "CVE-2026-47890"},
            new String[] {"ServerSentEvent", "CVE-2026-47890"},
            new String[] {"text/event-stream", "CVE-2026-47890"},
            new String[] {"RouterFunction", "CVE-2026-47892"},
            new String[] {"HandlerFunction", "CVE-2026-47892"},
            new String[] {"RequestPredicates", "CVE-2026-47892"},
            // 2026-08-20 일괄 공개 Spring Framework 6.2.x 14건 — 머지 뒤 OWASP 가 잡음(점검 문서 §4.1). 전제 조건별 토큰.
            new String[] {"UrlHandlerFilter", "CVE-2026-47883"},
            new String[] {"PartEvent", "CVE-2026-47885"},
            new String[] {"SpelExpressionParser", "CVE-2026-47886"},   // 신뢰할 수 없는 SpEL 평가 — 코어는 SpEL 파서를 쓰지 않는다
            new String[] {"UrlFileNameViewController", "CVE-2026-47887"},
            new String[] {"RSocket", "CVE-2026-47888"},
            new String[] {"org.eclipse.jetty", "CVE-2026-47889"},
            new String[] {"Jaxb2Decoder", "CVE-2026-47891"},
            new String[] {"HandshakeWebSocketService", "CVE-2026-47893"},
            new String[] {"SpringTemplateLoader", "CVE-2026-59280"},
            new String[] {"freemarker", "CVE-2026-59280"},
            new String[] {"EscapedErrors", "CVE-2026-59281"},
            new String[] {"AutoPopulatingList", "CVE-2026-59282"},
            new String[] {"SpelCompilerMode", "CVE-2026-59283"},
            new String[] {"spring.expression.compiler.mode", "CVE-2026-59283"},
            new String[] {"ServerResponse.sse", "CVE-2026-59313"},
            new String[] {"ContentDisposition", "CVE-2026-59314"},   // 신뢰할 수 없는 입력으로 만드는 Content-Disposition — 쓰게 되면 입력 검증과 함께 이 줄을 재검토
            new String[] {"reactor.netty.http.server", "CVE-2026-47874"},
            new String[] {"NettyReactiveWebServerFactory", "CVE-2026-47874"},
            new String[] {"ReactorHttpHandlerAdapter", "CVE-2026-47874"},
            new String[] {"HttpServer.create(", "CVE-2026-47874"});

    /** 빌드 파일에서 금지하는 의존성 — Reactor Netty 서버 전환(WebFlux 스타터)은 CVE-2026-47874 의 전제 조건이 된다. */
    private static final String[] FORBIDDEN_BUILD_DEPENDENCY = {"spring-boot-starter-webflux", "CVE-2026-47874"};

    @Test
    @DisplayName("main 소스에 XsltView·SSE 조각 렌더링·WebFlux 함수형 엔드포인트가 없다")
    void mainSourcesDoNotUseSuppressedAdvisoryPreconditions() throws IOException {
        Path root = repositoryRoot();
        List<String> violations = new ArrayList<>();
        int scanned = 0;
        for (String module : MODULES) {
            Path main = root.resolve(module).resolve("src/main");
            if (!Files.isDirectory(main)) continue;
            try (Stream<Path> files = Files.walk(main)) {
                for (Path file : files.filter(Files::isRegularFile).filter(SpringAdvisoryGuardTest::isSource).toList()) {
                    scanned++;
                    scan(file, root.relativize(file).toString().replace('\\', '/'), violations);
                }
            }
        }
        assertThat(scanned).as("검사한 main 소스 파일 수").isGreaterThan(100);
        assertThat(violations)
                .as("억제한 Spring 권고의 전제 조건이 코드에 들어왔다 — .trivyignore 의 해당 줄을 지우고 수정판으로 올려야 한다. 위반:\n"
                        + String.join("\n", violations))
                .isEmpty();
    }

    @Test
    @DisplayName("빌드 파일에 WebFlux 스타터(Reactor Netty 서버 전환)가 없다")
    void buildFilesDoNotSwitchToReactorNettyServer() throws IOException {
        Path root = repositoryRoot();
        List<String> violations = new ArrayList<>();
        for (String module : MODULES) {
            Path build = root.resolve(module).resolve("build.gradle.kts");
            if (!Files.isRegularFile(build)) continue;
            List<String> lines = Files.readAllLines(build);
            for (int i = 0; i < lines.size(); i++) {
                String code = lines.get(i).contains("//") ? lines.get(i).substring(0, lines.get(i).indexOf("//")) : lines.get(i);
                if (code.contains(FORBIDDEN_BUILD_DEPENDENCY[0])) {
                    violations.add(module + "/build.gradle.kts:" + (i + 1) + "  [" + FORBIDDEN_BUILD_DEPENDENCY[0] + " → "
                            + FORBIDDEN_BUILD_DEPENDENCY[1] + "]  " + lines.get(i).trim());
                }
            }
        }
        assertThat(violations)
                .as("Reactor Netty 를 서버로 쓰면 CVE-2026-47874(OSS 수정판은 Spring Boot 4) 의 전제 조건이 생긴다. 위반:\n"
                        + String.join("\n", violations))
                .isEmpty();
    }

    private static boolean isSource(Path p) {
        String n = p.toString();
        return n.endsWith(".java") || n.endsWith(".kt") || n.endsWith(".yml") || n.endsWith(".yaml") || n.endsWith(".properties");
    }

    private static void scan(Path file, String rel, List<String> violations) throws IOException {
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (MalformedInputException e) {
            return;
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String code = line.contains("//") ? line.substring(0, line.indexOf("//")) : line;
            String trimmed = code.trim();
            if (trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("#")) continue; // 주석 언급은 허용
            for (String[] token : FORBIDDEN) {
                if (code.contains(token[0])) {
                    violations.add(rel + ":" + (i + 1) + "  [" + token[0] + " → " + token[1] + "]  " + line.trim());
                }
            }
        }
    }

    private static Path repositoryRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("settings.gradle.kts"))) {
            dir = dir.getParent();
        }
        if (dir == null) throw new IllegalStateException("settings.gradle.kts 를 찾을 수 없습니다");
        return dir;
    }
}
