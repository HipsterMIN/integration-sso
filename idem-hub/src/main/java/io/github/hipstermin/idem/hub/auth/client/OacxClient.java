package io.github.hipstermin.idem.hub.auth.client;

import OACX.OacxException;
import OACX.OacxUtil;
import io.github.hipstermin.idem.hub.auth.config.AuthProperties;
import io.github.hipstermin.idem.hub.auth.dto.OacxAccessInfoResponse;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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
@Component
public class OacxClient {

    private final String providerKeyPath;
    private final boolean debugMode;

    public OacxClient(AuthProperties props) {
        this.providerKeyPath = props.oacx().providerKeyPath();
        this.debugMode = props.oacx().debugMode();
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
    public OacxAccessInfoResponse getAccessInfo(String fn) {
        log.info("[OACX] getAccessInfo 요청: fn={}", fn);

        if (providerKeyPath == null || providerKeyPath.isBlank()) {
            log.error("[OACX] providerKeyPath 미설정 — ido.auth.oacx.provider-key-path 확인 필요");
            return OacxAccessInfoResponse.builder()
                    .resultCode("5001")
                    .resultMsg("OACX 설정 오류: provider-key-path 미설정")
                    .build();
        }

        OacxUtil oacx = createOacxUtil();
        Map<String, String> accMap = oacx.getAccessInfo();
        log.info("[OACX] getAccessInfo 응답: status={}", accMap.get("status"));

        if (!"success".equals(accMap.get("status"))) {
            log.error("[OACX] 접근정보 발급 실패: status={}, message={}",
                    accMap.get("status"), accMap.get("message"));
            return OacxAccessInfoResponse.builder()
                    .resultCode("5001")
                    .resultMsg("OACX 접근정보 발급 실패: " + accMap.get("status"))
                    .build();
        }

        return OacxAccessInfoResponse.builder()
                .resultCode("2000")
                .resultMsg("성공")
                .fn(fn)
                .accKey(accMap.get("accKey"))
                .accToken(accMap.get("accToken"))
                .build();
    }

    /**
     * OACX 간편서명 콜백 JWT 복호화
     *
     * <p>OACX JS SDK가 전달한 콜백 데이터(fn, status, res)를 SDK를 통해 복호화.
     * 복호화 결과 Map에서 사용자 정보(name/userNm, phone/phoneNo, ci, birthday 등)를 추출.
     *
     * <p><b>OACX 인증 플로우에서의 위치 (Step 8~9):</b>
     * <pre>
     * Step 7: OACX JS SDK → FE 콜백 발생
     * Step 8: FE → POST /api/v1/auth/oacx/easysign (콜백 데이터 전달)
     * Step 9: ido → OacxUtil.jwtDecryptResult() 복호화
     * Step 10: ido → FE에 {name, birthday, phone} 반환 (CI 미포함 — Q3=B)
     * </pre>
     *
     * <p><b>복호화 결과 Map 주요 키:</b>
     * <ul>
     *   <li>{@code status} — "success" 또는 "error"</li>
     *   <li>{@code name} 또는 {@code userNm} — 이름 (provider마다 다름)</li>
     *   <li>{@code phone} 또는 {@code phoneNo} — 휴대폰 번호 (provider마다 다름)</li>
     *   <li>{@code birthday} — 생년월일 (일부 provider만 제공)</li>
     *   <li>{@code ci} — 연계정보 (CI 미반환 정책 Q3=B — AuthService에서 사용하지 않음)</li>
     * </ul>
     *
     * @param callbackData OACX JS SDK 콜백 전체 맵 ({fn, status, res} 포함)
     * @return 복호화 결과 Map ("status": "success" 또는 "error", 사용자 정보 포함)
     */
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
