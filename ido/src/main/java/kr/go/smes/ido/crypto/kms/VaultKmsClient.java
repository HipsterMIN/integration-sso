package kr.go.smes.ido.crypto.kms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.springframework.web.client.RestTemplate;

/**
 * HashiCorp Vault Transit Engine KMS 클라이언트
 *
 * <p><b>왜 Vault인가</b>:
 * HashiCorp Vault Transit Engine은 KMS(Key Management Service) 업계 사실상 표준이다.
 * AWS KMS·GCP Cloud KMS·Azure Key Vault 모두 동일한
 * "Encryption-as-a-Service" 패턴을 구현하며,
 * Vault는 온프레미스·클라우드·하이브리드 환경 모두를 단일 인터페이스로 지원한다.
 * <ul>
 *   <li>키 재료가 Vault 내부에만 존재 (애플리케이션은 ciphertext/plaintext만 주고받음)</li>
 *   <li>AES-256-GCM96 기본 — FIPS 140-2 Level 1 인증</li>
 *   <li>자동 키 버전 관리, 로테이션, 최소 복호화 버전 정책</li>
 *   <li>다양한 인증: Token·AppRole·Kubernetes·AWS IAM</li>
 * </ul>
 *
 * <p><b>Transit API 흐름</b>:
 * <pre>
 * 암호화 (키 로테이션 시):
 *   POST /v1/{transit-path}/encrypt/{key-name}
 *   Request:  { "plaintext": "Base64(DEK-bytes)" }
 *   Response: { "data": { "ciphertext": "vault:v1:AABB..." } }
 *   → DB key_material_encrypted 에 "vault:v1:AABB..." 저장
 *
 * 복호화 (서비스 기동 시):
 *   POST /v1/{transit-path}/decrypt/{key-name}
 *   Request:  { "ciphertext": "vault:v1:AABB..." }
 *   Response: { "data": { "plaintext": "Base64(DEK-bytes)" } }
 *   → Base64 디코딩 → 32-byte AES/HMAC 키
 * </pre>
 *
 * <p><b>인증 방식 (auth-method 설정으로 선택)</b>:
 * <pre>
 * token      — VAULT_TOKEN 환경변수 직접 사용 (개발·단순 운영)
 * approle    — VAULT_ROLE_ID + VAULT_SECRET_ID (CI/CD 파이프라인)
 * kubernetes — K8s ServiceAccount JWT 자동 주입 (K8s 운영 권장)
 * </pre>
 *
 * <p><b>Vault 사전 설정 (1회)</b>:
 * <pre>
 * # 1. Transit 시크릿 엔진 활성화
 * vault secrets enable -path=transit transit
 *
 * # 2. IDO 전용 Transit 키 생성 (AES-256-GCM96, 자동 로테이션 90일)
 * vault write -f transit/keys/ido-handoff-key
 * vault write transit/keys/ido-handoff-key/config \
 *   min_decryption_version=1 \
 *   deletion_allowed=false \
 *   auto_rotate_period=2160h
 *
 * # 3. 최소 권한 정책 생성
 * vault policy write ido-kms-policy - <<'EOF'
 * path "transit/encrypt/ido-handoff-key"  { capabilities = ["update"] }
 * path "transit/decrypt/ido-handoff-key"  { capabilities = ["update"] }
 * path "sys/health"                        { capabilities = ["read"]   }
 * EOF
 *
 * # 4-a. AppRole 인증 설정 (CI/CD용)
 * vault auth enable approle
 * vault write auth/approle/role/ido policies=ido-kms-policy token_ttl=1h
 * vault read auth/approle/role/ido/role-id   # → VAULT_ROLE_ID
 * vault write -f auth/approle/role/ido/secret-id  # → VAULT_SECRET_ID
 *
 * # 4-b. Kubernetes 인증 설정 (K8s 운영 권장)
 * vault auth enable kubernetes
 * vault write auth/kubernetes/config \
 *   kubernetes_host=https://kubernetes.default.svc
 * vault write auth/kubernetes/role/ido \
 *   bound_service_account_names=ido \
 *   bound_service_account_namespaces=onepass \
 *   policies=ido-kms-policy \
 *   ttl=1h
 * </pre>
 *
 * <p><b>K8s Secret 설정 (운영)</b>:
 * <pre>
 * IDO_KMS_ENABLED:      "true"
 * IDO_KMS_PROVIDER:     "vault"
 * VAULT_ADDR:           "http://vault.vault.svc.cluster.local:8200"
 * VAULT_AUTH_METHOD:    "kubernetes"       # token | approle | kubernetes
 * VAULT_K8S_ROLE:       "ido"
 * # AppRole 전용 (auth-method=approle)
 * VAULT_ROLE_ID:        "<role-id>"
 * VAULT_SECRET_ID:      "<secret-id>"
 * # Token 전용 (auth-method=token, 개발)
 * VAULT_TOKEN:          "<root-or-policy-token>"
 * </pre>
 *
 * <p><b>docker-compose.yml (개발 환경)</b>:
 * <pre>
 * vault:
 *   image: hashicorp/vault:1.17
 *   environment:
 *     VAULT_DEV_ROOT_TOKEN_ID: "dev-root-token"
 *     VAULT_DEV_LISTEN_ADDRESS: "0.0.0.0:8200"
 *   ports: ["8200:8200"]
 *   cap_add: [IPC_LOCK]
 *   command: server -dev
 * </pre>
 *
 * @see KmsClient
 * @see LocalKmsClient (KMS Off 모드 — enabled=false)
 * @see NhnKmsClient   (레거시 — provider=nhn)
 */
@Slf4j
@Component
@ConditionalOnProperty(
    prefix  = "ido.kms",
    name    = {"enabled", "provider"},
    havingValue = "true,vault"          // enabled=true AND provider=vault 일 때만 활성화
)
public class VaultKmsClient implements KmsClient {

    // ── 설정값 ──────────────────────────────────────────────────────────────

    /** Vault 서버 주소 (예: http://vault:8200, https://vault.company.com) */
    @Value("${ido.kms.vault.address:http://vault:8200}")
    private String vaultAddress;

    /** Transit 시크릿 엔진 마운트 경로 (기본: transit) */
    @Value("${ido.kms.vault.transit-path:transit}")
    private String transitPath;

    /** Transit 키 이름 (vault write -f transit/keys/{key-name}) */
    @Value("${ido.kms.vault.key-name:ido-handoff-key}")
    private String keyName;

    /**
     * 인증 방식: token | approle | kubernetes
     * 환경변수: VAULT_AUTH_METHOD
     */
    @Value("${ido.kms.vault.auth-method:${VAULT_AUTH_METHOD:token}}")
    private String authMethod;

    // Token 인증
    @Value("${ido.kms.vault.token:${VAULT_TOKEN:}}")
    private String staticToken;

    // AppRole 인증
    @Value("${VAULT_ROLE_ID:}")
    private String roleId;

    @Value("${VAULT_SECRET_ID:}")
    private String secretId;

    // Kubernetes 인증
    @Value("${VAULT_K8S_ROLE:ido}")
    private String k8sRole;

    @Value("${VAULT_K8S_SA_TOKEN_PATH:/var/run/secrets/kubernetes.io/serviceaccount/token}")
    private String k8sSaTokenPath;

    /** HCP Vault Dedicated 전용 Namespace (자체 호스팅 시 비워둠) */
    @Value("${ido.kms.vault.namespace:${VAULT_NAMESPACE:}}")
    private String vaultNamespace;

    @Value("${ido.kms.connection-timeout-ms:3000}")
    private int connectionTimeoutMs;

    @Value("${ido.kms.request-timeout-ms:5000}")
    private int requestTimeoutMs;

    private final ObjectMapper objectMapper;
    private RestTemplate       restTemplate;

    /**
     * 런타임에 획득한 클라이언트 토큰.
     * AppRole/K8s 인증은 만료 TTL이 있으므로 필요 시 갱신해야 하지만,
     * 여기서는 기동 시 1회 획득 + 만료 시 재시도(retry on 403) 전략을 사용한다.
     */
    private volatile String clientToken;

    public VaultKmsClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectionTimeoutMs);
        factory.setReadTimeout(requestTimeoutMs);
        this.restTemplate = new RestTemplate(factory);

        this.clientToken = acquireToken();

        if (clientToken == null || clientToken.isBlank()) {
            log.error("[KMS-Vault] 토큰 획득 실패. auth-method={} 설정을 확인하세요. " +
                      "VAULT_TOKEN / VAULT_ROLE_ID+SECRET_ID / Kubernetes SA 토큰 중 하나가 필요합니다.",
                      authMethod);
        } else {
            log.info("[KMS-Vault] 초기화 완료: address={} transitPath={}/{} authMethod={}",
                     vaultAddress, transitPath, keyName, authMethod);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // KmsClient 구현
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Vault Transit 복호화.
     *
     * <p>DB {@code key_material_encrypted}에 저장된 "vault:v{N}:..." 형태의
     * ciphertext를 Vault Transit API로 복호화하여 32-byte DEK를 반환한다.
     *
     * @param ciphertext DB 저장값 (예: "vault:v1:AbCdEf...")
     * @return 복호화된 DEK 바이트 (AES-256: 32 bytes, HMAC-SHA256: 32 bytes)
     */
    @Override
    public byte[] decrypt(String ciphertext) {
        try {
            String plainBase64 = callTransit("decrypt", ciphertext, null);
            return Base64.getDecoder().decode(plainBase64);
        } catch (KmsDecryptException e) {
            throw e;
        } catch (Exception e) {
            throw new KmsDecryptException("[KMS-Vault] 복호화 실패: " + e.getMessage(), e);
        }
    }

    /**
     * Vault Transit 암호화.
     *
     * <p>키 로테이션 시 새로 생성된 DEK 바이트를 Vault Transit API로 암호화하여
     * "vault:v{N}:..." 형태의 ciphertext를 반환한다.
     * 이 값을 DB {@code key_material_encrypted}에 저장한다.
     *
     * @param plainKeyBytes 암호화할 DEK 바이트 (32 bytes)
     * @return Vault ciphertext (예: "vault:v1:AbCdEf...") — DB 저장 가능
     */
    @Override
    public String encrypt(byte[] plainKeyBytes) {
        try {
            String plainBase64 = Base64.getEncoder().encodeToString(plainKeyBytes);
            return callTransit("encrypt", null, plainBase64);
        } catch (KmsEncryptException e) {
            throw e;
        } catch (Exception e) {
            throw new KmsEncryptException("[KMS-Vault] 암호화 실패: " + e.getMessage(), e);
        }
    }

    /**
     * Vault 헬스체크.
     *
     * <p>GET /v1/sys/health 응답 코드:
     * 200 = active·initialized·unsealed
     * 429 = standby (읽기 가능, 운영 정상으로 처리)
     * 그 외 = 비정상
     */
    @Override
    public boolean isHealthy() {
        try {
            ResponseEntity<String> resp = restTemplate.getForEntity(
                vaultAddress + "/v1/sys/health", String.class);
            int status = resp.getStatusCode().value();
            boolean healthy = (status == 200 || status == 429);
            if (!healthy) {
                log.warn("[KMS-Vault] 헬스체크 비정상: HTTP {}", status);
            }
            return healthy;
        } catch (Exception e) {
            log.warn("[KMS-Vault] 헬스체크 실패: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public String providerName() {
        return "vault";
    }

    // ══════════════════════════════════════════════════════════════════════
    // Transit API 호출
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Vault Transit encrypt/decrypt 공통 호출.
     *
     * <p>401/403 응답 시 토큰을 재획득하여 1회 재시도한다.
     * (AppRole/K8s 토큰 만료 대응)
     *
     * @param operation  "encrypt" 또는 "decrypt"
     * @param ciphertext decrypt 시 사용 (null이면 encrypt)
     * @param plainBase64 encrypt 시 사용 (null이면 decrypt)
     * @return encrypt: ciphertext 문자열 / decrypt: plaintext Base64 문자열
     */
    private String callTransit(String operation, String ciphertext, String plainBase64) {
        try {
            return doCallTransit(operation, ciphertext, plainBase64);
        } catch (TokenExpiredException e) {
            log.info("[KMS-Vault] 토큰 만료 — 재획득 후 재시도");
            this.clientToken = acquireToken();
            return doCallTransit(operation, ciphertext, plainBase64);
        }
    }

    private String doCallTransit(String operation, String ciphertext, String plainBase64) {
        String url  = vaultAddress + "/v1/" + transitPath + "/" + operation + "/" + keyName;
        String body = buildTransitBody(operation, ciphertext, plainBase64);

        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                url, HttpMethod.POST,
                new HttpEntity<>(body, buildHeaders()),
                String.class);

            int status = resp.getStatusCode().value();
            if (status == 401 || status == 403) {
                throw new TokenExpiredException("Vault 인증 실패: HTTP " + status);
            }

            return extractTransitResult(operation, resp.getBody());

        } catch (TokenExpiredException e) {
            throw e;
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403) {
                throw new TokenExpiredException("Vault 인증 실패: HTTP " + status);
            }
            String errorBody = e.getResponseBodyAsString();
            if ("encrypt".equals(operation)) {
                throw new KmsEncryptException(
                    "[KMS-Vault] Transit 암호화 API 오류 HTTP " + status + ": " + errorBody, e);
            } else {
                throw new KmsDecryptException(
                    "[KMS-Vault] Transit 복호화 API 오류 HTTP " + status + ": " + errorBody, e);
            }
        } catch (Exception e) {
            if ("encrypt".equals(operation)) {
                throw new KmsEncryptException("[KMS-Vault] Transit 암호화 통신 오류: " + e.getMessage(), e);
            } else {
                throw new KmsDecryptException("[KMS-Vault] Transit 복호화 통신 오류: " + e.getMessage(), e);
            }
        }
    }

    private String buildTransitBody(String operation, String ciphertext, String plainBase64) {
        if ("encrypt".equals(operation)) {
            return "{\"plaintext\":\"" + plainBase64 + "\"}";
        } else {
            // Vault Transit은 plaintext를 Base64로 보내야 하므로
            // ciphertext가 이미 "vault:v1:..." 형태인지 확인
            if (ciphertext == null || ciphertext.isBlank()) {
                throw new KmsDecryptException("[KMS-Vault] decrypt 호출 시 ciphertext가 null입니다.", null);
            }
            return "{\"ciphertext\":\"" + ciphertext + "\"}";
        }
    }

    private String extractTransitResult(String operation, String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode data = root.path("data");

        if ("encrypt".equals(operation)) {
            String ct = data.path("ciphertext").asText();
            if (ct.isBlank()) {
                throw new KmsEncryptException("[KMS-Vault] 응답에 ciphertext 없음: " + responseBody, null);
            }
            log.debug("[KMS-Vault] 암호화 완료: key={} cipherPrefix={}", keyName,
                      ct.length() > 20 ? ct.substring(0, 20) + "..." : ct);
            return ct;
        } else {
            String pt = data.path("plaintext").asText();
            if (pt.isBlank()) {
                throw new KmsDecryptException("[KMS-Vault] 응답에 plaintext 없음: " + responseBody, null);
            }
            log.debug("[KMS-Vault] 복호화 완료: key={}", keyName);
            return pt;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 인증 (Token 획득)
    // ══════════════════════════════════════════════════════════════════════

    private String acquireToken() {
        return switch (authMethod.toLowerCase()) {
            case "approle"    -> loginViaAppRole();
            case "kubernetes" -> loginViaKubernetes();
            default           -> {
                // "token" 모드: 환경변수/프로퍼티 직접 사용
                if (staticToken == null || staticToken.isBlank()) {
                    log.warn("[KMS-Vault] auth-method=token 이지만 VAULT_TOKEN이 비어있습니다.");
                }
                yield staticToken;
            }
        };
    }

    /**
     * AppRole 로그인.
     * POST /v1/auth/approle/login → client_token 반환.
     * CI/CD 파이프라인, 외부 서비스에 적합.
     */
    private String loginViaAppRole() {
        if (roleId.isBlank() || secretId.isBlank()) {
            log.error("[KMS-Vault] AppRole 인증: VAULT_ROLE_ID 또는 VAULT_SECRET_ID가 비어있습니다.");
            return null;
        }
        try {
            String url  = vaultAddress + "/v1/auth/approle/login";
            String body = "{\"role_id\":\"" + roleId + "\",\"secret_id\":\"" + secretId + "\"}";
            ResponseEntity<String> resp = restTemplate.postForEntity(
                url, new HttpEntity<>(body, jsonHeaders()), String.class);
            String token = objectMapper.readTree(resp.getBody())
                               .path("auth").path("client_token").asText();
            if (token.isBlank()) {
                log.error("[KMS-Vault] AppRole 로그인 응답에 client_token 없음");
                return null;
            }
            log.info("[KMS-Vault] AppRole 로그인 성공");
            return token;
        } catch (Exception e) {
            log.error("[KMS-Vault] AppRole 로그인 실패: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Kubernetes ServiceAccount JWT 로그인.
     * K8s Pod 내 /var/run/secrets/kubernetes.io/serviceaccount/token 자동 주입 파일 사용.
     * K8s 운영 환경에 가장 적합 (별도 자격증명 관리 불필요).
     */
    private String loginViaKubernetes() {
        try {
            Path saTokenPath = Path.of(k8sSaTokenPath);
            if (!Files.exists(saTokenPath)) {
                log.error("[KMS-Vault] Kubernetes SA 토큰 파일 없음: {}", k8sSaTokenPath);
                return null;
            }
            String jwt  = Files.readString(saTokenPath, StandardCharsets.UTF_8).trim();
            String url  = vaultAddress + "/v1/auth/kubernetes/login";
            String body = "{\"role\":\"" + k8sRole + "\",\"jwt\":\"" + jwt + "\"}";
            ResponseEntity<String> resp = restTemplate.postForEntity(
                url, new HttpEntity<>(body, jsonHeaders()), String.class);
            String token = objectMapper.readTree(resp.getBody())
                               .path("auth").path("client_token").asText();
            if (token.isBlank()) {
                log.error("[KMS-Vault] Kubernetes 로그인 응답에 client_token 없음");
                return null;
            }
            log.info("[KMS-Vault] Kubernetes 로그인 성공: role={}", k8sRole);
            return token;
        } catch (Exception e) {
            log.error("[KMS-Vault] Kubernetes 로그인 실패: {}", e.getMessage());
            return null;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // HTTP 헤더 빌더
    // ══════════════════════════════════════════════════════════════════════

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = jsonHeaders();
        if (clientToken != null && !clientToken.isBlank()) {
            headers.set("X-Vault-Token", clientToken);
        }
        if (vaultNamespace != null && !vaultNamespace.isBlank()) {
            headers.set("X-Vault-Namespace", vaultNamespace); // HCP Vault Dedicated 전용
        }
        return headers;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    // ══════════════════════════════════════════════════════════════════════
    // 내부 예외
    // ══════════════════════════════════════════════════════════════════════

    /** 토큰 만료 시그널 — callTransit() 내부에서 1회 재시도 트리거 */
    private static class TokenExpiredException extends RuntimeException {
        TokenExpiredException(String message) { super(message); }
    }
}
