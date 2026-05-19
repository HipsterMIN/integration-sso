package kr.go.smes.ido.broker.anyid;

import kr.go.smes.common.util.CorrelationIdHolder;
import kr.go.smes.common.util.UuidV7;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Any-ID 설치형 연동 전용 컨트롤러
 *
 * <p>행안부 Any-ID 설치형 인증 흐름의 진입점(initiate)과 콜백(callback)을 처리한다.<br>
 * 기관 #311 — 중소기업기술정보진흥원 (중소벤처24기업마당), srvc_no=1000001157
 *
 * <p><b>엔드포인트</b>:
 * <ul>
 *   <li>GET {@code /api/v1/anyid/{provider}/initiate}  — 인증 시작 → Any-ID UI 리다이렉트</li>
 *   <li>GET {@code /api/v1/anyid/{provider}/callback}  — Any-ID 콜백 수신 → 결과 처리</li>
 *   <li>GET {@code /api/v1/anyid/pid/initiate}         — 민간ID 인증 시작</li>
 *   <li>GET {@code /api/v1/anyid/pid/callback}         — 민간ID 콜백 수신</li>
 *   <li>GET {@code /api/v1/anyid/config}               — config.anyidc.json 조회 (FE용)</li>
 * </ul>
 *
 * <p><b>지원 provider 경로변수</b>:
 * <ul>
 *   <li>{@code mobile-id}      — 모바일 신분증 (L2)</li>
 *   <li>{@code easy-sign}      — 간편인증 (L1~L2)</li>
 *   <li>{@code joint-cert}     — 공동인증서 (L3)</li>
 *   <li>{@code financial-cert} — 금융인증서 (L3)</li>
 *   <li>{@code pid}            — 민간ID/소셜 (L1)</li>
 * </ul>
 *
 * <p><b>활성화 조건</b>:
 * <pre>
 * 이 컨트롤러는 IDO_BROKER_MODE 값과 무관하게 항상 등록된다.
 * BrokerService Layer 1(ProviderRouter)이 provider_type=NON_STANDARD으로 판별한
 * 인증수단은 AnyIdBrokerAdapter로 직접 라우팅된다.
 * FE는 /api/v1/anyid/{provider}/initiate를 직접 호출하거나
 * BrokerController → BrokerService → AnyIdBrokerAdapter 경로를 거친다.
 * </pre>
 *
 * @see AnyIdBrokerAdapter
 * @see AnyIdProperties
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/anyid")
@RequiredArgsConstructor
public class AnyIdController {

    private final AnyIdBrokerAdapter anyIdBrokerAdapter;
    private final AnyIdProperties    anyIdProperties;

    // ──────────────────────────────────────────────────────────────────────
    // 인증 시작
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Any-ID 인증 시작 — provider별 인증 UI 리다이렉트
     *
     * <p>FE에서 {@code window.location.href = '/api/v1/anyid/{provider}/initiate?returnUrl=...'} 호출.
     *
     * @param provider       인증 수단 (mobile-id / easy-sign / joint-cert / financial-cert / pid)
     * @param returnUrl      인증 완료 후 기관 귀환 URL
     * @param requestedLevel 요청 인증 수준 (L1/L2/L3)
     * @param correlationId  흐름 추적 ID (없으면 자동 생성)
     * @param socialProvider 민간ID 경우 소셜 provider (kakao / naver 등, optional)
     */
    @GetMapping("/{provider}/initiate")
    public ResponseEntity<Void> initiate(
            @PathVariable String provider,
            @RequestParam(required = false) String returnUrl,
            @RequestParam(defaultValue = "L1") String requestedLevel,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestParam(required = false) String socialProvider) {

        String cid = resolveCorrelationId(correlationId);

        log.info("[AnyIdController] 인증 시작: provider={} level={} cid={}", provider, requestedLevel, cid);

        try {
            String callbackUrl = buildCallbackUrl(provider, returnUrl, cid);
            String redirectUrl = anyIdBrokerAdapter.initiateAuth(
                    provider, cid, callbackUrl, returnUrl);

            HttpHeaders headers = new HttpHeaders();
            headers.setLocation(URI.create(redirectUrl));
            return ResponseEntity.status(HttpStatus.FOUND).headers(headers).build();

        } catch (Exception e) {
            log.error("[AnyIdController] 인증 시작 실패: provider={} cid={} err={}", provider, cid, e.getMessage());
            return redirectToError("ANYID_INIT_FAILED", e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 콜백 수신 (Any-ID → ido)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Any-ID 콜백 수신 — 인증 결과 처리 후 기관 URL로 리다이렉트
     *
     * <p>Any-ID 인증 완료 후 Any-ID 서버가 이 경로를 호출한다.
     *
     * @param provider      인증 수단 경로변수
     * @param txId          Any-ID 트랜잭션 ID
     * @param code          인증 완료 코드 (Any-ID 서버 발급)
     * @param returnUrl     기관 귀환 URL
     * @param error         인증 실패 코드 (실패 시)
     * @param correlationId 흐름 추적 ID
     */
    @GetMapping("/{provider}/callback")
    public ResponseEntity<Object> callback(
            @PathVariable String provider,
            @RequestParam(required = false) String txId,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String returnUrl,
            @RequestParam(required = false) String error,
            @RequestParam(value = "error_description", required = false) String errorDescription,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {

        String cid = resolveCorrelationId(correlationId);

        // Any-ID 인증 실패
        if (error != null) {
            log.warn("[AnyIdController] 인증 실패 콜백: error={} desc={} cid={}", error, errorDescription, cid);
            return redirectToError("ANYID_AUTH_FAILED", error);
        }

        if (txId == null || txId.isBlank()) {
            log.warn("[AnyIdController] txId 없음: cid={}", cid);
            return redirectToError("MISSING_TX_ID", "transaction id is missing");
        }

        log.info("[AnyIdController] 콜백 수신: provider={} txId={} cid={}", provider, txId, cid);

        try {
            // Any-ID 인증 결과 검증
            AnyIdAuthResult result = anyIdBrokerAdapter.verifyCallback(provider, cid, txId, code);

            if (!result.isSuccess()) {
                return redirectToError("ANYID_VERIFY_FAILED",
                        "result_code=" + result.getResultCode());
            }

            // TODO: 1. AuthResult 저장 (NonOidcAuthService.processAuth() 연동)
            //       2. FeSessionService.create() → feSessionId 쿠키 발급
            //       3. returnUrl 리다이렉트
            // PoC: 결과 로그 후 returnUrl 리다이렉트
            log.info("[AnyIdController] 인증 완료: provider={} authLevel={} cid={}",
                    provider, result.getAuthLevel(), cid);

            String redirectTarget = (returnUrl != null && !returnUrl.isBlank())
                    ? returnUrl : "/conversion/complete";

            HttpHeaders headers = new HttpHeaders();
            headers.setLocation(URI.create(redirectTarget));
            return ResponseEntity.status(HttpStatus.FOUND).headers(headers).build();

        } catch (Exception e) {
            log.error("[AnyIdController] 콜백 처리 실패: provider={} cid={} err={}",
                    provider, cid, e.getMessage());
            return redirectToError("ANYID_CALLBACK_ERROR", e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // config.anyidc.json 정보 제공 (FE용)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Any-ID 설정 정보 반환 (FE 클라이언트용)
     *
     * <p>FE에서 인증 수단별 script_url, css_url 등을 동적으로 로드하기 위해 호출.
     * 민감 정보(API 키 등)는 포함하지 않는다.
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        Map<String, Object> config = Map.of(
                "srvc_no",     anyIdProperties.getSrvcNo(),
                "agency_code", anyIdProperties.getAgencyCode(),
                "agency_name", anyIdProperties.getAgencyName(),
                "auth_base",   anyIdProperties.getAuth().resolvedBaseUrl(),
                "sso_base",    anyIdProperties.getSso().resolvedBaseUrl(),
                "portal_base", anyIdProperties.getPortal().resolvedBaseUrl(),
                "pid_url",     anyIdProperties.getPid().getProviderUrl(),
                "config_file", anyIdProperties.getConfigFile()
        );
        return ResponseEntity.ok(config);
    }

    // ──────────────────────────────────────────────────────────────────────
    // 헬스 체크
    // ──────────────────────────────────────────────────────────────────────

    /** Any-ID 연동 상태 확인 */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status",      "UP",
                "srvc_no",     anyIdProperties.getSrvcNo(),
                "auth_domain", anyIdProperties.getAuth().getDomain(),
                "sso_domain",  anyIdProperties.getSso().getDomain(),
                "kms_host",    anyIdProperties.getKms().getServerHost(),
                "enc_alg",     anyIdProperties.getKms().getEncAlg()
        ));
    }

    // ──────────────────────────────────────────────────────────────────────
    // 내부 유틸
    // ──────────────────────────────────────────────────────────────────────

    private String resolveCorrelationId(String header) {
        if (header != null && !header.isBlank()) {
            CorrelationIdHolder.set(header);
            return header;
        }
        String existing = CorrelationIdHolder.get();
        if (existing != null && !existing.isBlank()) return existing;
        String generated = UuidV7.generate();
        CorrelationIdHolder.set(generated);
        return generated;
    }

    private String buildCallbackUrl(String provider, String returnUrl, String correlationId) {
        StringBuilder sb = new StringBuilder("/api/v1/anyid/")
                .append(provider)
                .append("/callback")
                .append("?cid=").append(encodeParam(correlationId));
        if (returnUrl != null && !returnUrl.isBlank()) {
            sb.append("&returnUrl=").append(encodeParam(returnUrl));
        }
        return sb.toString();
    }

    private ResponseEntity<Void> redirectToError(String code, String detail) {
        String errorUrl = "/error?code=" + code
                + (detail != null ? "&detail=" + encodeParam(detail) : "");
        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create(errorUrl));
        return ResponseEntity.status(HttpStatus.FOUND).headers(headers).build();
    }

    private String encodeParam(String value) {
        if (value == null) return "";
        try {
            return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
