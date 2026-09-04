package kr.go.smes.ido.crypto.kms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.go.smes.ido.broker.anyid.AnyIdProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import java.util.Base64;

/**
 * Any-ID KMS(Key Distribution Service) 클라이언트 — ARIA-CBC-256 키 배포 연동
 *
 * <p><b>Any-ID KMS 개요</b>:<br>
 * 행안부 Any-ID 사업단이 운영하는 키 관리 서비스(kdist).
 * ARIA-CBC-256 알고리즘으로 서비스 별 암호화 키를 배포·갱신한다.
 *
 * <p><b>NHN SKM({@link NhnKmsClient})과의 차이</b>:
 * <table>
 *   <tr><th>항목</th><th>NHN SKM</th><th>Any-ID KMS</th></tr>
 *   <tr><td>알고리즘</td><td>AES-256</td><td>ARIA-CBC-256</td></tr>
 *   <tr><td>운영주체</td><td>NHN Cloud</td><td>행안부 Any-ID 사업단</td></tr>
 *   <tr><td>용도</td><td>Handoff AES/HMAC DEK 보호</td><td>Any-ID 인증 세션 키</td></tr>
 *   <tr><td>Profile</td><td>prod</td><td>항상 활성화 (anyid 모드)</td></tr>
 * </table>
 *
 * <p><b>설정 키</b>:
 * <pre>
 * ido.anyid.kms.server-host   : https://www.anyid.dev:8119/   (개발)
 * ido.anyid.kms.enc-alg       : ARIA-CBC-256
 * ido.anyid.kms.cversion      : 1
 * ido.anyid.kms.app-key       : (Base64, K8s Secret ANYID_KMS_APP_KEY)
 * ido.anyid.kms.client-info   : (Base64, K8s Secret ANYID_KMS_CLIENT_INFO)
 * </pre>
 *
 * <p><b>kdist API 흐름</b>:
 * <pre>
 *   1. POST {kms_server_host}kdist/v1/key/request
 *      Body: { srvc_no, app_key, client_info, cversion, enc_alg }
 *   2. 응답: { result_code, result_msg, enc_key (Base64 ARIA-CBC-256 암호문) }
 *   3. enc_key → ARIA-CBC-256 복호화 → 평문 키 바이트
 * </pre>
 *
 * <p><b>ARIA-CBC-256</b>:<br>
 * 한국 표준 블록 암호(KS X 1213-1). BC(Bouncy Castle) 라이브러리를 사용하여 처리.
 * 키 요청·응답 암복호화는 Any-ID 사업단 제공 SDK 규격을 따른다.
 *
 * <p><b>보안 주의</b>:<br>
 * {@code app_key} / {@code client_info}는 기관별 고유 비밀키로,
 * 절대 소스코드나 Git에 평문으로 저장하지 않는다.
 * 운영 환경에서는 반드시 K8s Secret / Vault에서 주입한다.
 *
 * @see NhnKmsClient
 * @see NoOpKmsClient
 * @see KmsClient
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "ido.anyid.kms", name = "app-key", matchIfMissing = false)
public class AnyIdKmsClient implements KmsClient {

    private static final String PROVIDER_NAME = "anyid-kms";

    private final AnyIdProperties anyIdProperties;
    private final ObjectMapper    objectMapper;
    private RestTemplate          restTemplate;

    public AnyIdKmsClient(AnyIdProperties anyIdProperties, ObjectMapper objectMapper) {
        this.anyIdProperties = anyIdProperties;
        this.objectMapper    = objectMapper;
    }

    @PostConstruct
    void init() {
        AnyIdProperties.Kms kms = anyIdProperties.getKms();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(kms.getConnectTimeoutMs());
        factory.setReadTimeout(kms.getReadTimeoutMs());
        this.restTemplate = new RestTemplate(factory);

        if (kms.getAppKey() == null || kms.getAppKey().isBlank()) {
            log.warn("[AnyId-KMS] ido.anyid.kms.app-key 미설정 — " +
                     "Any-ID KMS 키 요청이 실패할 수 있습니다. " +
                     "운영 환경: K8s Secret ANYID_KMS_APP_KEY를 설정하세요.");
        } else {
            log.info("[AnyId-KMS] Any-ID KMS 클라이언트 초기화: host={} alg={} srvc_no={}",
                    kms.getServerHost(), kms.getEncAlg(), anyIdProperties.getSrvcNo());
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // KmsClient 구현
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Any-ID KMS에서 암호화 키를 요청·복호화하여 반환한다.
     *
     * <p>{@code encryptedKeyBase64}는 Any-ID kdist 서버에서 받은 암호화된 키 값
     * (ARIA-CBC-256 암호문, Base64). KMS API로 복호화하거나, 이미 평문 키라면
     * Base64 디코딩만 수행한다.
     *
     * <p>※ Any-ID 설치형에서 키는 주로 kdist API 호출 결과로 동적 수신하므로,
     * 이 메서드는 캐시된 enc_key 값의 복호화에 활용된다.
     *
     * @param encryptedKeyBase64 Any-ID kdist 응답의 enc_key (Base64)
     * @return 복호화된 키 바이트
     */
    @Override
    public byte[] decrypt(String encryptedKeyBase64) {
        validateConfig();
        try {
            log.debug("[AnyId-KMS] 키 복호화 요청 (ARIA-CBC-256)");
            return decryptAriaKey(encryptedKeyBase64);
        } catch (KmsDecryptException e) {
            throw e;
        } catch (Exception e) {
            throw new KmsDecryptException("[AnyId-KMS] 복호화 실패: " + e.getMessage(), e);
        }
    }

    /**
     * Any-ID KMS에 새 암호화 키 등록을 요청한다.
     *
     * <p>Any-ID kdist는 키 등록 API를 별도 제공하지 않으며,
     * 키 배포는 행안부 사업단을 통해 이루어진다.
     * 이 메서드는 Base64 인코딩만 수행한다 (패스스루).
     */
    @Override
    public String encrypt(byte[] plainKeyBytes) {
        log.warn("[AnyId-KMS] encrypt() 호출 — Any-ID KMS는 키 등록 API를 제공하지 않습니다. " +
                 "Base64 인코딩만 수행합니다. 실제 키 배포는 행안부 Any-ID 사업단에 문의하세요.");
        return Base64.getEncoder().encodeToString(plainKeyBytes);
    }

    /**
     * Any-ID KMS 연결 상태 확인
     *
     * <p>kdist 서버 ping: {@code GET {kms_server_host}kdist/v1/health}
     */
    @Override
    public boolean isHealthy() {
        AnyIdProperties.Kms kms = anyIdProperties.getKms();
        if (kms.getAppKey() == null || kms.getAppKey().isBlank()) {
            return false;
        }
        try {
            String url = kms.getServerHost().replaceAll("/$", "") + "/kdist/v1/health";
            ResponseEntity<String> resp = restTemplate.getForEntity(url, String.class);
            boolean healthy = resp.getStatusCode().is2xxSuccessful();
            if (healthy) {
                log.debug("[AnyId-KMS] 헬스체크 성공");
            }
            return healthy;
        } catch (Exception e) {
            log.warn("[AnyId-KMS] 헬스체크 실패: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Any-ID kdist API 호출
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Any-ID kdist API로 키 요청 후 ARIA-CBC-256 복호화 수행
     *
     * <p>API: {@code POST {kms_server_host}kdist/v1/key/request}
     *
     * <p>요청 Body (JSON):
     * <pre>
     * {
     *   "srvc_no":     "1000001157",
     *   "app_key":     "{Base64 앱 키}",
     *   "client_info": "{Base64 클라이언트 정보}",
     *   "cversion":    1,
     *   "enc_alg":     "ARIA-CBC-256"
     * }
     * </pre>
     *
     * <p>응답 Body (JSON):
     * <pre>
     * {
     *   "result_code": "0000",
     *   "result_msg":  "success",
     *   "enc_key":     "{ARIA-CBC-256 암호문, Base64}"
     * }
     * </pre>
     */
    public byte[] requestKeyFromKdist() {
        AnyIdProperties.Kms kms = anyIdProperties.getKms();
        validateConfig();

        String url = kms.getServerHost().replaceAll("/$", "") + "/kdist/v1/key/request";

        String requestBody = buildKdistRequestBody(kms);
        log.debug("[AnyId-KMS] kdist 키 요청: url={} srvc_no={}", url, anyIdProperties.getSrvcNo());

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, String.class);

            JsonNode root = objectMapper.readTree(response.getBody());
            assertKdistSuccess(root);

            String encKey = root.path("enc_key").asText();
            if (encKey.isBlank()) {
                throw new KmsDecryptException("[AnyId-KMS] kdist 응답에 enc_key 없음", null);
            }

            log.info("[AnyId-KMS] kdist 키 수신 성공: srvc_no={}", anyIdProperties.getSrvcNo());
            return decryptAriaKey(encKey);

        } catch (KmsDecryptException e) {
            throw e;
        } catch (Exception e) {
            throw new KmsDecryptException(
                    "[AnyId-KMS] kdist 키 요청 실패: " + e.getMessage(), e);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // ARIA-CBC-256 복호화
    // ══════════════════════════════════════════════════════════════════════

    /**
     * ARIA-CBC-256 복호화 — Any-ID 배포 키 복호화
     *
     * <p>ARIA(국산 블록 암호, KS X 1213-1) CBC 모드, 256-bit 키.
     * BC(Bouncy Castle) Provider 사용: {@code ARIA/CBC/PKCS5Padding}
     *
     * <p>Any-ID 규약:
     * <ul>
     *   <li>app_key를 Base64 디코딩한 원시 바이트를 ARIA-256 키로 사용</li>
     *   <li>IV: enc_key 앞 16바이트 (프리픽스)</li>
     *   <li>암호문: enc_key 16바이트 이후 나머지</li>
     * </ul>
     *
     * <p>Bouncy Castle 의존성 필요 (build.gradle):
     * <pre>
     *   implementation 'org.bouncycastle:bcprov-jdk18on:1.78.1'
     * </pre>
     *
     * @param encKeyBase64 ARIA-CBC-256 암호문 (Base64)
     * @return 복호화된 키 바이트
     */
    private byte[] decryptAriaKey(String encKeyBase64) {
        AnyIdProperties.Kms kms = anyIdProperties.getKms();

        try {
            byte[] encKeyBytes = decodeBase64Flexible(encKeyBase64);
            byte[] appKeyBytes = decodeBase64Flexible(kms.getAppKey());

            // IV: 첫 16바이트, 암호문: 나머지
            if (encKeyBytes.length <= 16) {
                throw new KmsDecryptException(
                        "[AnyId-KMS] enc_key 길이 부족 (≤16 bytes): Base64 길이=" + encKeyBase64.length(), null);
            }
            byte[] iv         = new byte[16];
            byte[] ciphertext = new byte[encKeyBytes.length - 16];
            System.arraycopy(encKeyBytes, 0, iv, 0, 16);
            System.arraycopy(encKeyBytes, 16, ciphertext, 0, ciphertext.length);

            // Bouncy Castle ARIA/CBC/PKCS7 복호화
            return ariacbcDecrypt(appKeyBytes, iv, ciphertext);

        } catch (KmsDecryptException e) {
            throw e;
        } catch (Exception e) {
            throw new KmsDecryptException("[AnyId-KMS] ARIA-CBC-256 복호화 실패: " + e.getMessage(), e);
        }
    }

    /**
     * Bouncy Castle을 이용한 ARIA/CBC/PKCS7Padding 복호화.
     *
     * <p>BC Provider가 클래스패스에 없으면 {@link KmsDecryptException}을 던진다.
     * Bouncy Castle 의존성({@code org.bouncycastle:bcprov-jdk18on}) 필요.
     *
     * @param keyBytes   256-bit ARIA 키 (32 bytes)
     * @param iv         초기화 벡터 (16 bytes)
     * @param ciphertext 암호문 바이트
     * @return 복호화된 평문 바이트
     */
    @SuppressWarnings("unchecked")
    private byte[] ariacbcDecrypt(byte[] keyBytes, byte[] iv, byte[] ciphertext) {
        try {
            // Bouncy Castle 동적 로딩 (컴파일 의존성 최소화)
            Class<?> engineClass  = Class.forName("org.bouncycastle.crypto.engines.ARIAEngine");
            Class<?> paramClass   = Class.forName("org.bouncycastle.crypto.params.ParametersWithIV");
            Class<?> keyParamClass = Class.forName("org.bouncycastle.crypto.params.KeyParameter");
            Class<?> paddingClass = Class.forName("org.bouncycastle.crypto.paddings.PKCS7Padding");
            Class<?> modeClass    = Class.forName("org.bouncycastle.crypto.modes.CBCBlockCipher");
            Class<?> cipherClass  = Class.forName("org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher");

            Object ariaEngine   = engineClass.getDeclaredConstructor().newInstance();
            Object cbcMode      = modeClass.getDeclaredConstructor(
                    Class.forName("org.bouncycastle.crypto.BlockCipher")).newInstance(ariaEngine);
            Object pkcs7Padding = paddingClass.getDeclaredConstructor().newInstance();
            Object cipher       = cipherClass.getDeclaredConstructor(
                    Class.forName("org.bouncycastle.crypto.BlockCipher"),
                    Class.forName("org.bouncycastle.crypto.paddings.BlockCipherPadding")
            ).newInstance(cbcMode, pkcs7Padding);

            Object keyParam = keyParamClass.getDeclaredConstructor(byte[].class).newInstance((Object) keyBytes);
            Object params   = paramClass.getDeclaredConstructor(
                    Class.forName("org.bouncycastle.crypto.CipherParameters"),
                    byte[].class).newInstance(keyParam, iv);

            cipher.getClass().getMethod("init", boolean.class,
                    Class.forName("org.bouncycastle.crypto.CipherParameters"))
                    .invoke(cipher, false, params);

            int outputSize = (int) cipher.getClass().getMethod("getOutputSize", int.class)
                    .invoke(cipher, ciphertext.length);
            byte[] output = new byte[outputSize];

            int bytesProcessed = (int) cipher.getClass()
                    .getMethod("processBytes", byte[].class, int.class, int.class, byte[].class, int.class)
                    .invoke(cipher, ciphertext, 0, ciphertext.length, output, 0);

            int finalBytes = (int) cipher.getClass()
                    .getMethod("doFinal", byte[].class, int.class)
                    .invoke(cipher, output, bytesProcessed);

            int totalLen = bytesProcessed + finalBytes;
            byte[] plaintext = new byte[totalLen];
            System.arraycopy(output, 0, plaintext, 0, totalLen);

            log.debug("[AnyId-KMS] ARIA-CBC-256 복호화 성공: 출력 {}bytes", totalLen);
            return plaintext;

        } catch (ClassNotFoundException e) {
            throw new KmsDecryptException(
                    "[AnyId-KMS] Bouncy Castle 라이브러리 없음. " +
                    "build.gradle에 'org.bouncycastle:bcprov-jdk18on:1.78.1' 추가 필요. " +
                    "원인: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new KmsDecryptException(
                    "[AnyId-KMS] ARIA/CBC/PKCS7 복호화 실패: " + e.getMessage(), e);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 유틸
    // ══════════════════════════════════════════════════════════════════════

    private String buildKdistRequestBody(AnyIdProperties.Kms kms) {
        return String.format(
                "{\"srvc_no\":\"%s\",\"app_key\":\"%s\",\"client_info\":\"%s\"," +
                "\"cversion\":%d,\"enc_alg\":\"%s\"}",
                anyIdProperties.getSrvcNo(),
                kms.getAppKey(),
                kms.getClientInfo(),
                kms.getCversion(),
                kms.getEncAlg()
        );
    }

    /** Any-ID kdist 응답 성공 여부 검증 */
    private void assertKdistSuccess(JsonNode root) {
        String resultCode = root.path("result_code").asText("9999");
        if (!"0000".equals(resultCode)) {
            String resultMsg = root.path("result_msg").asText("unknown");
            throw new KmsDecryptException(
                    String.format("[AnyId-KMS] kdist API 오류: result_code=%s result_msg=%s",
                            resultCode, resultMsg), null);
        }
    }

    /** Base64 디코딩 (URL-safe / standard / padding 유연 처리) */
    private byte[] decodeBase64Flexible(String b64) {
        if (b64 == null || b64.isBlank()) {
            throw new KmsDecryptException("[AnyId-KMS] Base64 입력값이 비어 있습니다.", null);
        }
        String std = b64.replace('-', '+').replace('_', '/');
        int pad = std.length() % 4;
        if (pad == 2) std += "==";
        else if (pad == 3) std += "=";
        try {
            return Base64.getDecoder().decode(std);
        } catch (IllegalArgumentException e) {
            throw new KmsDecryptException("[AnyId-KMS] Base64 디코딩 실패: " + e.getMessage(), e);
        }
    }

    private void validateConfig() {
        AnyIdProperties.Kms kms = anyIdProperties.getKms();
        if (kms.getAppKey() == null || kms.getAppKey().isBlank()) {
            throw new KmsDecryptException(
                    "[AnyId-KMS] ido.anyid.kms.app-key가 설정되지 않았습니다. " +
                    "K8s Secret ANYID_KMS_APP_KEY를 환경변수로 주입하세요.", null);
        }
    }
}
