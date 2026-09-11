package io.github.hipstermin.idem.plugin.anyid.sdk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.plugin.anyid.AnyIdProperties;
import io.github.hipstermin.idem.plugin.anyid.SsobDecryptor;
import java.io.File;
import java.util.Map;
import kr.or.anyid.auth.AnyidAuth;
import kr.or.anyid.util.AnyidCertRef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ResourceLoader;

/**
 * Any-ID 설치형 SDK ssob 복호화 — {@link SsobDecryptor} 기본 구현
 *
 * <p>anyid-auth-sdk / anyid-auth-util-sdk 의 핵심 API를 래핑한다. 이 클래스만 {@code kr.or.anyid} 를 참조하므로
 * SDK jar 가 없는 빌드에서는 {@code sdk} 패키지 전체가 컴파일에서 빠진다.
 *
 * <h3>ssob 생명 주기</h3>
 * <ol>
 *   <li><b>생성</b>: 각 인증 수단의 {@code ExtractConfigurer.build()} → {@code ExtractConfigurer}
 *       내부에서 {@code AnyidCertRef.createSsob()} 호출 → 암호화된 ssob 반환</li>
 *   <li><b>전달</b>: FE 인증 UI → {@code anyidAdaptor.success(data)} 콜백으로
 *       {@code data.ssob / data.txId / data.tag} 수신</li>
 *   <li><b>복호화</b>: 이 서비스의 {@link #decryptSsob(String, String, String)} 호출 →
 *       {@code AnyidCertRef.decryptSsob(ssob, tag, kdistPath)}</li>
 * </ol>
 *
 * <h3>ssob 내부 필드 (복호화 후)</h3>
 * <pre>
 * {
 *   "ci"      : "연계정보(CI) — SHA-256 해싱 후 identifierHash 사용",
 *   "authLvl" : "인증 수준 (1=L1, 2=L2, 3=L3)",
 *   "name"    : "성명 (화면 표시용)",
 *   "brdt"    : "생년월일 (일부 수단에서만)",
 *   "clientIp": "클라이언트 IP (extract.jsp에서 삽입)",
 *   "vid"     : "가상주민번호 (AnySign 계열)",
 *   "vidRandom": "VID 랜덤 (AnySign 계열)"
 * }
 * </pre>
 *
 * <h3>kdist-api.json 경로 결정</h3>
 * <pre>
 * Spring 환경: {@link AnyIdProperties.Kms#getKdistConfig()} classpath 또는 절대 경로
 * WAS 직접 배포(JSP): {@code PropertiesManager.kdistConfigPath} 자동 결정
 * </pre>
 *
 * @see AnyIdProperties
 */
@Slf4j
public class AnyIdSdkSsobDecryptor implements SsobDecryptor {

    private final AnyIdProperties anyIdProperties;
    private final ObjectMapper    objectMapper;
    private final ResourceLoader  resourceLoader;

    /** kdist-api.json 절대 경로 (생성 시 결정) */
    private String kdistAbsPath;

    public AnyIdSdkSsobDecryptor(AnyIdProperties anyIdProperties, ObjectMapper objectMapper, ResourceLoader resourceLoader) {
        this.anyIdProperties = anyIdProperties;
        this.objectMapper = objectMapper;
        this.resourceLoader = resourceLoader;
        init();
    }

    private void init() {
        try {
            String configPath = anyIdProperties.getKms().getKdistConfig();
            if (configPath != null && configPath.startsWith("classpath:")) {
                File file = resourceLoader.getResource(configPath).getFile();
                kdistAbsPath = file.getAbsolutePath();
            } else if (configPath != null && !configPath.isBlank()) {
                kdistAbsPath = configPath;
            } else {
                // 기본값: classpath 에서 kdist-local.json 탐색
                File file = resourceLoader.getResource("classpath:config/anyid/kdist-local.json").getFile();
                kdistAbsPath = file.getAbsolutePath();
            }
            log.info("[AnyId-Ssob] kdist-api.json 경로 결정: {}", kdistAbsPath);
        } catch (Exception e) {
            log.warn("[AnyId-Ssob] kdist-api.json 파일 접근 실패 (테스트 환경 허용): {}", e.getMessage());
            // 테스트/PoC 환경에서는 경로 없이도 Bean 생성 허용
            kdistAbsPath = null;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // ssob 복호화
    // ══════════════════════════════════════════════════════════════════════

    /**
     * ssob 문자열 복호화 → CI/authLevel 등 Map 반환
     *
     * <p>anyidAdaptor.success(data) 콜백의 {@code data.ssob} 와 {@code data.tag(=txId)} 를
     * 인자로 받아 {@code AnyidCertRef.decryptSsob()} 를 호출한다.
     *
     * <pre>
     * // FE anyidAdaptor.js 참조:
     * anyidAdaptor.orgLogin = function(data) {
     *     var obj = { ssob: data.ssob, tag: params.get("tx") }  // tag = txId
     *     // POST /api/v1/anyid/{provider}/ssob 로 전송
     * }
     * </pre>
     *
     * @param ssobStr      암호화된 ssob 문자열 (FE에서 전송)
     * @param tag          암호화 태그 — txId 와 동일 (AnyidC.LOAD_MODULE의 tag 파라미터)
     * @param kdistPath    kdist-api.json 절대 경로 (null이면 {@link #kdistAbsPath} 사용)
     * @return 복호화된 ssob 필드 Map ({@code ci, authLvl, name, brdt, clientIp} 등)
     * @throws PlatformException ssob 복호화 실패 시
     */
    @Override
    public Map<String, Object> decrypt(String ssob, String tag) {
        return decryptSsob(ssob, tag, null);
    }

    public Map<String, Object> decryptSsob(String ssobStr, String tag, String kdistPath) {
        String effectivePath = (kdistPath != null && !kdistPath.isBlank())
                ? kdistPath : kdistAbsPath;

        if (effectivePath == null) {
            throw new PlatformException(PlatformErrorCode.IDP_RESPONSE_INVALID, tag,
                    "kdist-api.json 경로 미설정 — ssob 복호화 불가");
        }

        log.debug("[AnyId-Ssob] decryptSsob 호출: tag={} kdistPath={}", tag, effectivePath);

        try {
            // SDK 핵심 API 호출
            // AnyidAuth: readValueAsString 등 유틸 메서드 제공
            // AnyidCertRef: decryptSsob(ssobStr, tag, kdistPath) → Map<String, Object>
            AnyidCertRef anyidCertRef = new AnyidCertRef();

            // decryptSsob 반환: { "ssobStr": "{ \"ci\":..., \"authLvl\":... }", "status": "success" }
            Map<String, Object> resultMap = anyidCertRef.decryptSsob(ssobStr, tag, effectivePath);

            String status = (String) resultMap.get("status");
            if (!"success".equalsIgnoreCase(status)) {
                String msg = (String) resultMap.getOrDefault("message", "unknown error");
                log.error("[AnyId-Ssob] ssob 복호화 실패: tag={} status={} msg={}", tag, status, msg);
                throw new PlatformException(PlatformErrorCode.IDP_RESPONSE_INVALID, tag,
                        "ssob 복호화 실패: " + msg);
            }

            // 내부 ssobStr → Map으로 파싱
            String ssobJsonStr = (String) resultMap.get("ssobStr");
            Map<String, Object> ssob = objectMapper.readValue(
                    ssobJsonStr,
                    new TypeReference<Map<String, Object>>() {}
            );

            log.info("[AnyId-Ssob] ssob 복호화 성공: authLvl={} tag={}", ssob.get("authLvl"), tag);
            return ssob;

        } catch (PlatformException e) {
            throw e;
        } catch (Exception e) {
            log.error("[AnyId-Ssob] decryptSsob 예외: tag={} err={}", tag, e.getMessage(), e);
            throw new PlatformException(PlatformErrorCode.IDP_RESPONSE_INVALID, tag,
                    "ssob 복호화 중 예외: " + e.getMessage());
        }
    }

    /**
     * AnyidAuth 인스턴스 생성 (SDK 유틸 접근용)
     *
     * <p>SDK 내 {@code AnyidAuth#binToHex()}, {@code AnyidAuth#deriveKey()} 등
     * 유틸 메서드 활용 시 이 메서드로 인스턴스를 얻는다.
     *
     * @return {@code kr.or.anyid.auth.AnyidAuth} 인스턴스
     */
    public AnyidAuth createAnyidAuth() {
        return new AnyidAuth();
    }
}
