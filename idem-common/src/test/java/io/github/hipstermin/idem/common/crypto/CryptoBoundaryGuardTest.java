package io.github.hipstermin.idem.common.crypto;

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
 * D2-b 암호 경계 가드 — 코어 모듈의 main 소스는 JCA·BouncyCastle 을 직접 부르지 않고 {@link CryptoProvider} 만 쓴다
 * ({@code docs/generalization-plan.md} D2, {@code docs/execution-plan.md} P2 "JCE 직접 호출 0건").
 *
 * <p>허용: {@code common/crypto/jca/}(유일한 JCA 구현). 키 타입({@code java.security.PublicKey/PrivateKey/KeyPair})과
 * JWT 라이브러리(jjwt)·TLS 설정은 연산이 아니므로 대상이 아니다. 플러그인·relay·샘플·SDK 는 대상 밖(벤더 규격·JDK8 호환 사유).
 */
@DisplayName("암호 경계 가드 — 코어는 CryptoProvider 만 쓴다")
class CryptoBoundaryGuardTest {

    private static final List<String> CORE_MODULES = List.of(
            "idem-common", "idem-gate", "idem-registry", "idem-hub", "idem-authz", "idem-relay");

    /** 직접 호출 금지 토큰 (import 와 FQN 인라인 사용 모두 잡는다). */
    private static final List<String> FORBIDDEN = List.of(
            "javax.crypto.",
            "java.security.MessageDigest",
            "java.security.SecureRandom",
            "java.security.Signature",
            "java.security.KeyFactory",
            "java.security.KeyPairGenerator",
            "java.security.spec.",
            "org.bouncycastle",
            "MessageDigest.getInstance(",
            "MessageDigest.isEqual(",
            "Mac.getInstance(",
            "Cipher.getInstance(",
            "SecretKeyFactory.getInstance(",
            "new SecureRandom(",
            "KeyPairGenerator.getInstance(",
            "KeyFactory.getInstance(",
            "Signature.getInstance(");

    private static final List<String> ALLOWLIST = List.of(
            "idem-common/src/main/java/io/github/hipstermin/idem/common/crypto/jca/");

    @Test
    @DisplayName("코어 main 소스에 JCA·BouncyCastle 직접 호출이 없다")
    void coreDoesNotCallJcaDirectly() throws IOException {
        Path root = repositoryRoot();
        List<String> violations = new ArrayList<>();
        for (String module : CORE_MODULES) {
            Path main = root.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(main)) continue;
            try (Stream<Path> files = Files.walk(main)) {
                for (Path file : files.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".java")).toList()) {
                    String rel = root.relativize(file).toString().replace('\\', '/');
                    if (ALLOWLIST.stream().anyMatch(rel::startsWith)) continue;
                    scan(file, rel, violations);
                }
            }
        }
        assertThat(violations)
                .as("코어의 암호 연산은 CryptoProvider(CryptoProviders.current()) 를 거쳐야 한다. 위반:\n" + String.join("\n", violations))
                .isEmpty();
    }

    @Test
    @DisplayName("허용 목록 경로는 실제로 존재한다")
    void allowlistPathsExist() {
        Path root = repositoryRoot();
        for (String rel : ALLOWLIST) {
            assertThat(Files.exists(root.resolve(rel))).as(rel).isTrue();
        }
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
            if (code.trim().startsWith("*") || code.trim().startsWith("/*")) continue; // javadoc 언급은 허용
            for (String token : FORBIDDEN) {
                if (code.contains(token)) {
                    violations.add(rel + ":" + (i + 1) + "  [" + token + "]  " + line.trim());
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
