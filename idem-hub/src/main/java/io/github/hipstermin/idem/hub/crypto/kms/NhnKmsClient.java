package io.github.hipstermin.idem.hub.crypto.kms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * NHN Cloud Secure Key Manager 클라이언트 — 운영(prod) 환경 KMS 구현 (S9-T8)
 *
 * <p><b>NHN Cloud Secure Key Manager 개요</b>:
 * NHN Cloud가 제공하는 관리형 키 관리 서비스.
 * 대칭 키, 비대칭 키, 기밀 데이터를 안전하게 저장하고 API로 암/복호화.
 *
 * <p><b>지원하는 두 가지 사용 패턴</b>:
 * <ol>
 *   <li><b>Envelope Encryption</b> (기본 패턴):
 *       DB {@code key_material_encrypted}에 저장된 암호문을
 *       NHN SKM 대칭 키로 복호화하여 로컬 AES 키를 얻음.
 *       {@code POST /symmetric-keys/{keyid}/decrypt} 사용.
 *   </li>
 *   <li><b>기밀 데이터 직접 조회</b> (단순 패턴):
 *       NHN SKM에 키 재료 원본을 기밀 데이터로 저장하고 API로 직접 조회.
 *       {@code GET /secrets/{keyid}} 사용.
 *       이 경우 DB key_material_encrypted 컬럼은 keyid만 저장.
 *   </li>
 * </ol>
 *
 * <p><b>인증 방식</b>:
 * NHN Cloud Appkey를 URL 경로에 포함하여 요청.
 * 키 저장소 접근 인증: IP 인증 (K8s Pod CIDR 등록 필요) 또는 MAC 인증.
 *
 * <p><b>API 엔드포인트</b>:
 * <pre>
 * Global: https://api-keymanager.nhncloudservice.com
 * </pre>
 *
 * <p><b>운영 설정 예시 ({@code application.yml})</b>:
 * <pre>
 * ido:
 *   kms:
 *     enabled:  true
 *     provider: nhn
 *     nhn:
 *       appkey: ${NHN_SKM_APPKEY}         # NHN Cloud 프로젝트 Appkey
 *       aes-key-id: ${NHN_SKM_AES_KEY_ID} # Handoff AES DEK 암호화용 대칭 키 ID
 *       hmac-key-id: ${NHN_SKM_HMAC_KEY_ID}
 *       # mode: envelope | secret
 *       # envelope: DB에 저장된 ciphertext를 SKM 대칭 키로 복호화
 *       # secret: SKM 기밀 데이터에서 키 재료 원본을 직접 조회
 *       mode: ${NHN_SKM_MODE:secret}
 * </pre>
 *
 * <p><b>K8s Secret에 추가할 항목</b>:
 * <pre>
 * NHN_SKM_APPKEY: "발급받은 Appkey"
 * NHN_SKM_AES_KEY_ID: "생성한 대칭 키 ID (UUID)"
 * NHN_SKM_HMAC_KEY_ID: "생성한 대칭 키 ID (UUID)"
 * </pre>
 *
 * <p><b>NHN SKM 콘솔 사전 준비</b>:
 * <ol>
 *   <li>Security > Secure Key Manager 활성화</li>
 *   <li>키 저장소 생성 (IP 인증: K8s Node CIDR 등록)</li>
 *   <li>대칭 키(AES-256) 생성 — Handoff AES, Handoff HMAC 각 1개</li>
 *   <li>mode=secret 사용 시: 기밀 데이터로 Base64 인코딩된 32바이트 키 저장</li>
 * </ol>
 *
 * @see NoOpKmsClient
 * @see KmsClient
 */
@Slf4j
@Component
@Primary   // 일반 KMS(NoOp/Local/Nhn/Vault)는 ido.kms.provider 로 상호배타 활성 — 단일 KmsClient 주입의 정본
// 1.0.1: @ConditionalOnProperty(name={"enabled","provider"}, havingValue="true,nhn") 는 두 속성이 각각 "true,nhn" 와 같아야 해 절대 참이 되지 않았다
@ConditionalOnExpression("'${idem.hub.kms.enabled:false}' == 'true' && '${idem.hub.kms.provider:}' == 'nhn'")
public class NhnKmsClient implements KmsClient {

    /** NHN SKM API 기본 엔드포인트 (Global) */
    private static final String DEFAULT_ENDPOINT = "https://api-keymanager.nhncloudservice.com";

    /**
     * SKM 운영 모드
     * <ul>
     *   <li>ENVELOPE: DB 암호문 → SKM 대칭 키 복호화 → 평문 AES 키</li>
     *   <li>SECRET:   SKM 기밀 데이터 직접 조회 → 평문 AES 키 (더 단순)</li>
     * </ul>
     */
    private enum SkmMode { ENVELOPE, SECRET }

    @Value("${idem.hub.kms.nhn.endpoint:" + DEFAULT_ENDPOINT + "}")
    private String endpoint;

    @Value("${idem.hub.kms.nhn.appkey:}")
    private String appkey;

    /** Envelope 모드: AES DEK를 암호화한 SKM 대칭 키 ID */
    @Value("${idem.hub.kms.nhn.aes-key-id:}")
    private String aesSymKeyId;

    /** Envelope 모드: HMAC DEK를 암호화한 SKM 대칭 키 ID */
    @Value("${idem.hub.kms.nhn.hmac-key-id:}")
    private String hmacSymKeyId;

    /**
     * Secret 모드: SKM에 기밀 데이터로 저장된 AES 키 ID.
     * Envelope 모드에서는 미사용.
     */
    @Value("${idem.hub.kms.nhn.aes-secret-id:${idem.hub.kms.nhn.aes-key-id:}}")
    private String aesSecretId;

    @Value("${idem.hub.kms.nhn.hmac-secret-id:${idem.hub.kms.nhn.hmac-key-id:}}")
    private String hmacSecretId;

    /** 운영 모드: secret(기본) | envelope */
    @Value("${idem.hub.kms.nhn.mode:secret}")
    private String modeProp;

    @Value("${idem.hub.kms.connection-timeout-ms:3000}")
    private int connectionTimeoutMs;

    @Value("${idem.hub.kms.request-timeout-ms:5000}")
    private int requestTimeoutMs;

    private final ObjectMapper objectMapper;
    private RestTemplate restTemplate;
    private SkmMode mode;

    public NhnKmsClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void init() {
        this.mode = "envelope".equalsIgnoreCase(modeProp) ? SkmMode.ENVELOPE : SkmMode.SECRET;

        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectionTimeoutMs);
        factory.setReadTimeout(requestTimeoutMs);
        this.restTemplate = new RestTemplate(factory);

        if (appkey == null || appkey.isBlank()) {
            log.error("[KMS-NHN] idem.hub.kms.nhn.appkey 설정 없음. " +
                    "NHN Cloud Secure Key Manager Appkey를 K8s Secret에 설정하세요.");
        } else {
            log.info("[KMS-NHN] NHN SKM 클라이언트 초기화: endpoint={} mode={}",
                    endpoint, mode);
        }
    }

    /**
     * NHN SKM을 이용하여 암호화된 키 재료(DEK)를 복호화한다.
     *
     * <p><b>SECRET 모드</b>: {@code encryptedKeyBase64}를 SKM 기밀 데이터 keyId로 해석하여
     * {@code GET /secrets/{keyid}}로 평문 키를 직접 조회.
     * DB의 {@code key_material_encrypted} 컬럼에 keyId를 저장하는 패턴.
     *
     * <p><b>ENVELOPE 모드</b>: {@code encryptedKeyBase64}를 SKM 대칭 키로 복호화.
     * {@code POST /symmetric-keys/{keyid}/decrypt} 호출.
     *
     * @param encryptedKeyBase64 SKM 기밀 keyId 또는 SKM 암호문
     * @return 복호화된 키 바이트 (32 bytes)
     */
    @Override
    public byte[] decrypt(String encryptedKeyBase64) {
        validateAppkey();
        try {
            if (mode == SkmMode.SECRET) {
                return decryptViaSecret(encryptedKeyBase64);
            } else {
                return decryptViaSymmetricKey(encryptedKeyBase64, aesSymKeyId);
            }
        } catch (KmsDecryptException e) {
            throw e;
        } catch (Exception e) {
            throw new KmsDecryptException("[KMS-NHN] 복호화 실패: " + e.getMessage(), e);
        }
    }

    /**
     * NHN SKM 대칭 키로 평문 키 바이트를 암호화한다.
     *
     * <p><b>SECRET 모드</b>: SKM 기밀 데이터로 저장하는 방식이므로
     * 이 메서드는 직접 암호화 대신 기밀 데이터 ID를 반환.
     * (실제 기밀 데이터 저장은 NHN Cloud 콘솔/API로 사전 수행)
     *
     * <p><b>ENVELOPE 모드</b>: SKM 대칭 키로 Base64 평문 데이터를 암호화.
     * {@code POST /symmetric-keys/{keyid}/encrypt} 호출.
     *
     * @param plainKeyBytes 암호화할 키 바이트
     * @return 암호화된 키 (Base64 문자열 or SKM 기밀 keyId)
     */
    @Override
    public String encrypt(byte[] plainKeyBytes) {
        validateAppkey();
        if (mode == SkmMode.SECRET) {
            // SECRET 모드: NHN 콘솔에서 기밀 데이터로 사전 등록 후 keyId를 반환하는 방식
            // 이 메서드는 rotate 시 자동 암호화 경로에서만 호출됨
            log.warn("[KMS-NHN] SECRET 모드에서는 encrypt()를 직접 호출하지 않습니다. " +
                    "NHN Cloud 콘솔에서 기밀 데이터를 등록 후 keyId를 설정하세요.");
            throw new KmsEncryptException(
                    "[KMS-NHN] SECRET 모드: 키 등록은 NHN Cloud 콘솔/API로 수행하세요.", null);
        }
        try {
            return encryptViaSymmetricKey(plainKeyBytes, aesSymKeyId);
        } catch (KmsEncryptException e) {
            throw e;
        } catch (Exception e) {
            throw new KmsEncryptException("[KMS-NHN] 암호화 실패: " + e.getMessage(), e);
        }
    }

    /**
     * NHN SKM API 헬스 체크 — 클라이언트 정보 조회 API 호출
     * {@code GET /keymanager/v1.0/appkey/{appkey}/confirm}
     */
    @Override
    public boolean isHealthy() {
        if (appkey == null || appkey.isBlank()) return false;
        try {
            String url = endpoint + "/keymanager/v1.0/appkey/" + appkey + "/confirm";
            ResponseEntity<String> resp = restTemplate.getForEntity(url, String.class);
            JsonNode root = objectMapper.readTree(resp.getBody());
            boolean success = root.path("header").path("isSuccessful").asBoolean(false);
            if (success) {
                log.debug("[KMS-NHN] 헬스체크 성공");
            }
            return success;
        } catch (Exception e) {
            log.warn("[KMS-NHN] 헬스체크 실패: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public String providerName() {
        return "nhn-skm";
    }

    // ── SECRET 모드 ────────────────────────────────────────────────────────

    /**
     * NHN SKM 기밀 데이터 조회로 키 재료 획득
     *
     * <p>API: {@code GET /keymanager/v1.0/appkey/{appkey}/secrets/{keyid}}
     *
     * <p>응답 body.secret 값이 Base64 인코딩된 32바이트 키 재료라고 가정.
     * 실제 저장 시 {@code Base64.getEncoder().encodeToString(rawKeyBytes)} 형태로 저장.
     *
     * @param secretKeyId SKM 기밀 데이터 keyId (DB key_material_encrypted 컬럼에 저장된 값)
     * @return 복호화된 키 바이트 (32 bytes)
     */
    private byte[] decryptViaSecret(String secretKeyId) {
        String url = endpoint + "/keymanager/v1.0/appkey/" + appkey
                + "/secrets/" + secretKeyId.trim();

        log.debug("[KMS-NHN] 기밀 데이터 조회: keyId={}", secretKeyId);

        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());

            assertSkmSuccess(root, "기밀 데이터 조회");

            String secret = root.path("body").path("secret").asText();
            if (secret.isBlank()) {
                throw new KmsDecryptException("[KMS-NHN] 기밀 데이터가 비어 있음: keyId=" + secretKeyId, null);
            }

            // 기밀 데이터는 Base64 인코딩된 키 재료
            return decodeBase64Flexible(secret);

        } catch (KmsDecryptException e) {
            throw e;
        } catch (Exception e) {
            throw new KmsDecryptException(
                    "[KMS-NHN] 기밀 데이터 조회 실패: keyId=" + secretKeyId + " err=" + e.getMessage(), e);
        }
    }

    // ── ENVELOPE 모드 ──────────────────────────────────────────────────────

    /**
     * NHN SKM 대칭 키 복호화 API
     *
     * <p>API: {@code POST /keymanager/v1.0/appkey/{appkey}/symmetric-keys/{keyid}/decrypt}
     *
     * @param ciphertextBase64 SKM 대칭 키로 암호화된 DEK (Base64)
     * @param symKeyId         SKM 대칭 키 ID
     * @return 복호화된 키 바이트
     */
    private byte[] decryptViaSymmetricKey(String ciphertextBase64, String symKeyId) {
        if (symKeyId == null || symKeyId.isBlank()) {
            throw new KmsDecryptException(
                    "[KMS-NHN] ENVELOPE 모드: idem.hub.kms.nhn.aes-key-id가 설정되지 않았습니다.", null);
        }

        String url = endpoint + "/keymanager/v1.0/appkey/" + appkey
                + "/symmetric-keys/" + symKeyId + "/decrypt";

        String requestBody = "{\"ciphertext\":\"" + ciphertextBase64 + "\"}";
        log.debug("[KMS-NHN] 대칭 키 복호화 요청: keyId={}", symKeyId);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());

            assertSkmSuccess(root, "대칭 키 복호화");

            String plaintext = root.path("body").path("plaintext").asText();
            if (plaintext.isBlank()) {
                throw new KmsDecryptException("[KMS-NHN] 복호화 결과가 비어 있음: keyId=" + symKeyId, null);
            }

            // plaintext는 SKM이 반환하는 원본 문자열 — Base64로 저장했다면 디코딩
            return decodeBase64Flexible(plaintext);

        } catch (KmsDecryptException e) {
            throw e;
        } catch (Exception e) {
            throw new KmsDecryptException(
                    "[KMS-NHN] 대칭 키 복호화 실패: keyId=" + symKeyId + " err=" + e.getMessage(), e);
        }
    }

    /**
     * NHN SKM 대칭 키 암호화 API
     *
     * <p>API: {@code POST /keymanager/v1.0/appkey/{appkey}/symmetric-keys/{keyid}/encrypt}
     *
     * @param plainKeyBytes 암호화할 키 바이트
     * @param symKeyId      SKM 대칭 키 ID
     * @return 암호화된 ciphertext (Base64)
     */
    private String encryptViaSymmetricKey(byte[] plainKeyBytes, String symKeyId) {
        if (symKeyId == null || symKeyId.isBlank()) {
            throw new KmsEncryptException(
                    "[KMS-NHN] ENVELOPE 모드: idem.hub.kms.nhn.aes-key-id가 설정되지 않았습니다.", null);
        }

        String url = endpoint + "/keymanager/v1.0/appkey/" + appkey
                + "/symmetric-keys/" + symKeyId + "/encrypt";

        String plainBase64 = Base64.getEncoder().encodeToString(plainKeyBytes);
        String requestBody = "{\"plaintext\":\"" + plainBase64 + "\"}";
        log.debug("[KMS-NHN] 대칭 키 암호화 요청: keyId={}", symKeyId);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());

            assertSkmSuccess(root, "대칭 키 암호화");

            String ciphertext = root.path("body").path("ciphertext").asText();
            if (ciphertext.isBlank()) {
                throw new KmsEncryptException("[KMS-NHN] 암호화 결과가 비어 있음: keyId=" + symKeyId, null);
            }
            return ciphertext;

        } catch (KmsEncryptException e) {
            throw e;
        } catch (Exception e) {
            throw new KmsEncryptException(
                    "[KMS-NHN] 대칭 키 암호화 실패: keyId=" + symKeyId + " err=" + e.getMessage(), e);
        }
    }

    // ── 공통 헬퍼 ─────────────────────────────────────────────────────────

    /**
     * NHN SKM 공통 응답 헤더 검증
     *
     * <p>NHN SKM 응답 구조:
     * <pre>
     * {
     *   "header": { "resultCode": 0, "resultMessage": "success", "isSuccessful": true },
     *   "body": { ... }
     * }
     * </pre>
     */
    private void assertSkmSuccess(JsonNode root, String operation) {
        JsonNode header = root.path("header");
        boolean success = header.path("isSuccessful").asBoolean(false);
        if (!success) {
            int code    = header.path("resultCode").asInt(-1);
            String msg  = header.path("resultMessage").asText("unknown");
            throw new KmsDecryptException(
                    String.format("[KMS-NHN] %s API 오류: resultCode=%d message=%s", operation, code, msg), null);
        }
    }

    /**
     * Base64 문자열 디코딩 (URL-safe / standard / padding 유연 처리)
     */
    private byte[] decodeBase64Flexible(String b64) {
        String std = b64.replace('-', '+').replace('_', '/');
        int pad = std.length() % 4;
        if (pad == 2) std += "==";
        else if (pad == 3) std += "=";
        return Base64.getDecoder().decode(std);
    }

    private void validateAppkey() {
        if (appkey == null || appkey.isBlank()) {
            throw new KmsDecryptException(
                    "[KMS-NHN] idem.hub.kms.nhn.appkey가 설정되지 않았습니다. " +
                    "NHN Cloud Secure Key Manager Appkey를 K8s Secret에 추가하세요.", null);
        }
    }
}
