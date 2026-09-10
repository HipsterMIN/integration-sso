package io.github.hipstermin.idem.plugin.niceoacx.oacx;

import OACX.OacxException;
import OACX.OacxUtil;
import io.github.hipstermin.idem.plugin.niceoacx.OacxProperties;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * OACX 전자서명 중계모듈 SDK 래퍼 클라이언트
 *
 * <p>OACX SDK({@code OACX-SDK-v1.3.2.jar})를 래핑하여 두 가지 기능을 제공:
 * <ol>
 *   <li>{@link #getAccessInfo(String)} — 접근키/토큰 발급 (FE SDK 초기화용)</li>
 *   <li>{@link #decryptEasysignResult(Map)} — 간편서명 JWT 복호화</li>
 * </ol>
 *
 * <p><b>OACX SDK 특성:</b>
 * <ul>
 *   <li>HTTP 클라이언트가 아닌 네이티브 SDK (JAR 파일 직접 의존)</li>
 *   <li>매 요청마다 {@code OacxUtil} 인스턴스를 새로 생성 (SDK 내부 상태 없음)</li>
 *   <li>provider key JSON 파일을 매 요청마다 로드 ({@code loadJSONInfo(path)})</li>
 * </ul>
 *
 * <p><b>OACX provider별 복호화 결과 키 차이:</b>
 * <ul>
 *   <li>naver, toss, dream, banksalad: {@code name}, {@code phone} 키</li>
 *   <li>PASS (SKT/KT/LGU+ 간편인증): {@code userNm}, {@code phoneNo} 키</li>
 * </ul>
 * 키 통일 처리는 {@code AuthService.handleOacxEasysign()}에서 수행.
 *
 * <p><b>설정 위치:</b> {@code ido.auth.oacx.provider-key-path}
 *
 * @see io.github.hipstermin.idem.hub.auth.service.AuthService
 */
@Slf4j
public class OacxClientAdapter {

    private final String providerKeyPath;
    private final boolean debugMode;

    public OacxClientAdapter(OacxProperties props) {
        this.providerKeyPath = props.getProviderKeyPath();
        this.debugMode = props.isDebugMode();
        log.info("[OacxClient] 초기화 — providerKeyPath={}, debugMode={}",
                providerKeyPath.isEmpty() ? "(미설정)" : providerKeyPath, debugMode);
    }

    /**
     * OACX 접근키/토큰 발급
     *
     * <p>FE의 OACX JS SDK 초기화에 필요한 accKey, accToken을 발급한다.
     * SDK가 OACX 서버와 통신하여 단기 유효 접근 정보를 반환.
     *
     * <p><b>OACX 인증 플로우에서의 위치 (Step 1~3):</b>
     * <pre>
     * Step 1: FE → POST /api/v1/auth/oacx/access-info
     * Step 2: ido → OacxUtil.getAccessInfo() 호출
     * Step 3: ido → FE에 {fn, accKey, accToken} 반환
     * Step 4: FE → OACX JS SDK 초기화
     * </pre>
     *
     * @param fn OACX 기능 코드 (현재: "simpleAuth")
     * @return OACX 접근 정보 응답 DTO
     */
    /** 접근 정보(accKey·accToken) 발급. 실패는 IdentityVerificationException(reasonCode 5001). */
    public Map<String, String> getAccessInfo(String fn) {
        log.info("[OACX] getAccessInfo 요청: fn={}", fn);
        if (providerKeyPath == null || providerKeyPath.isBlank()) {
            throw new io.github.hipstermin.idem.common.spi.identity.IdentityVerificationException(
                    OacxEasySignIdentityVerificationProvider.CODE, "5001", "OACX 설정 오류: provider-key-path 미설정");
        }
        OacxUtil oacx = createOacxUtil();
        Map<String, String> accMap = oacx.getAccessInfo();
        if (!"success".equals(accMap.get("status"))) {
            log.error("[OACX] 접근정보 발급 실패: status={}, message={}", accMap.get("status"), accMap.get("message"));
            throw new io.github.hipstermin.idem.common.spi.identity.IdentityVerificationException(
                    OacxEasySignIdentityVerificationProvider.CODE, "5001", "OACX 접근정보 발급 실패: " + accMap.get("status"));
        }
        return Map.of("fn", fn == null ? "" : fn, "accKey", accMap.get("accKey"), "accToken", accMap.get("accToken"));
    }

    public Map<String, String> decryptEasysignResult(Map<String, Object> callbackData) {
        log.info("[OACX] jwtDecryptResult 호출: fn={}, status={}",
                callbackData.get("fn"), callbackData.get("status"));

        if (providerKeyPath == null || providerKeyPath.isBlank()) {
            log.error("[OACX] providerKeyPath 미설정 — 복호화 불가");
            return Map.of("status", "error", "message", "OACX 설정 오류: provider-key-path 미설정");
        }

        try {
            OacxUtil oacx = createOacxUtil();
            Map<String, String> result = oacx.jwtDecryptResult(callbackData);
            log.info("[OACX] jwtDecryptResult 결과: status={}, keys={}", result.get("status"), result.keySet());
            return result;
        } catch (OacxException e) {
            log.error("[OACX] jwtDecryptResult 복호화 실패: {}", e.getMessage(), e);
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    /**
     * OacxUtil 인스턴스 생성 (매 요청마다 신규 생성)
     *
     * <p>OACX SDK는 인스턴스별 상태를 가지지 않으므로 매 요청마다 생성해도 무방.
     * provider key JSON을 로드하여 SDK를 초기화.
     */
    private OacxUtil createOacxUtil() {
        OacxUtil oacx = new OacxUtil();
        oacx.setDebugMode(debugMode);
        oacx.loadJSONInfo(providerKeyPath);
        return oacx;
    }
}
