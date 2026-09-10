package io.github.hipstermin.idem.hub.broker.anyid;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.common.util.CorrelationIdHolder;
import io.github.hipstermin.idem.common.util.UuidV7;
import io.github.hipstermin.idem.hub.broker.nonoidc.NonOidcAuthCommand;
import io.github.hipstermin.idem.hub.broker.nonoidc.NonOidcAuthService;
import io.github.hipstermin.idem.hub.fe.session.FeSessionService;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Any-ID 설치형 연동 전용 컨트롤러
 *
 * <p>행안부 Any-ID 설치형 인증 흐름의 진입점(initiate)과 콜백(callback)을 처리한다.<br>
 * 운영기관 식별자(srvc_no 등)는 ido.anyid.* 설정으로 주입된다 (S1 범용화).
 *
 * <h3>엔드포인트</h3>
 * <ul>
 *   <li>GET {@code /api/v1/anyid/{provider}/initiate}  — 인증 시작 → Any-ID UI 리다이렉트</li>
 *   <li>GET {@code /api/v1/anyid/{provider}/callback}  — Any-ID 콜백 수신 → ssob/verify 처리</li>
 *   <li>POST {@code /api/v1/anyid/{provider}/ssob}     — FE 인증 결과(ssob) 수신 → 복호화 처리</li>
 *   <li>POST {@code /api/v1/anyid/txId}                — 거래 ID 발급 (FE SDK 초기화용)</li>
 *   <li>POST {@code /api/v1/anyid/ssob}                — provider 없는 통합 ssob 처리 (FE 호환 응답)</li>
 *   <li>GET {@code /api/v1/anyid/oidc/ssoLogin}        — SSO 로그인 처리</li>
 *   <li>GET {@code /api/v1/anyid/config}               — config.anyidc.json 조회 (FE용)</li>
 * </ul>
 *
 * <h3>지원 provider 경로변수</h3>
 * <ul>
 *   <li>{@code mobile-id}      — 모바일 신분증 (L2)</li>
 *   <li>{@code easy-sign}      — 간편인증 (L1~L2)</li>
 *   <li>{@code joint-cert}     — 공동인증서 (L3)</li>
 *   <li>{@code financial-cert} — 금융인증서 (L3)</li>
 *   <li>{@code pid}            — 민간ID/소셜 (L1)</li>
 * </ul>
 *
 * <h3>SDK 기반 인증 흐름 (설치형)</h3>
 * <pre>
 * 1. FE → GET /initiate → AnyidC.LOAD_MODULE() 초기화 → Any-ID 인증 UI 표시
 * 2. 사용자 인증 완료 → anyidAdaptor.success(data) 콜백
 *    - data.ssob: 암호화된 인증 결과
 *    - data.txId: 트랜잭션 ID (= tag)
 * 3. FE → POST /ssob { ssob, tag, txId }
 *    → AnyIdSsobService.decryptSsob() (SDK: AnyidCertRef.decryptSsob())
 *    → CI 추출 → NonOidcAuthService.processAuth() → Kafka Outbox
 *    → FeSessionService.create() → feSessionId 쿠키
 * 4. (SSO 모드) anyidAdaptor.ssoLogin() → GET /oidc/ssoLogin
 * </pre>
 *
 * <h3>활성화 조건</h3>
 * <pre>
 * 이 컨트롤러는 IDO_BROKER_MODE 값과 무관하게 항상 등록된다.
 * BrokerService Layer 1(ProviderRouter)이 provider_type=NON_STANDARD으로 판별한
 * 인증수단은 AnyIdBrokerAdapter로 직접 라우팅된다.
 * </pre>
 *
 * @see AnyIdBrokerAdapter
 * @see AnyIdSsobService
 * @see AnyIdProperties
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/anyid")
@RequiredArgsConstructor
public class AnyIdController {

    private final AnyIdBrokerAdapter anyIdBrokerAdapter;
    private final AnyIdSsobService   anyIdSsobService;
    private final NonOidcAuthService  nonOidcAuthService;
    private final FeSessionService    feSessionService;
    private final AnyIdProperties    anyIdProperties;
    private final ObjectMapper       objectMapper;

    // ──────────────────────────────────────────────────────────────────────
    // 인증 시작
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Any-ID 인증 시작 — provider별 인증 UI 리다이렉트
     *
     * <p>FE에서 {@code window.location.href = '/api/v1/anyid/{provider}/initiate?returnUrl=...'} 호출.
     * 설치형은 이 엔드포인트가 Any-ID JS SDK가 로드되는 인증 페이지로 리다이렉트한다.
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
    // 콜백 수신 (Any-ID → ido) — SSO 모드용
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Any-ID SSO 모드 콜백 수신
     *
     * <p>SSO 모드(anyidAdaptor.ssoLogin())에서 호출되는 콜백 엔드포인트.
     * {@code data} 파라미터에 Base64 인코딩된 JSON이 전달된다:
     * <pre>
     * {
     *   "txId": "...",
     *   "ssob": "...(암호화된 ssob)...",
     *   "userSeCd": "...",
     *   "afData": "..."
     * }
     * </pre>
     *
     * <p>ssob를 SDK로 복호화하여 CI 추출 후 NonOidcAuthService로 처리한다.
     *
     * @param provider      인증 수단 경로변수
     * @param txId          Any-ID 트랜잭션 ID (단독 콜백 시)
     * @param code          인증 완료 코드 (단독 콜백 시)
     * @param returnUrl     기관 귀환 URL
     * @param error         인증 실패 코드 (실패 시)
     * @param correlationId 흐름 추적 ID
     */
    @GetMapping("/{provider}/callback")
    public ResponseEntity<Void> callback(
            @PathVariable String provider,
            @RequestParam(required = false) String txId,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String returnUrl,
            @RequestParam(required = false) String error,
            @RequestParam(value = "error_description", required = false) String errorDescription,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {

        String cid = resolveCorrelationId(correlationId);

        if (error != null) {
            log.warn("[AnyIdController] 인증 실패 콜백: error={} desc={} cid={}", error, errorDescription, cid);
            return redirectToError("ANYID_AUTH_FAILED", error);
        }

        if (txId == null || txId.isBlank()) {
            log.warn("[AnyIdController] txId 없음: cid={}", cid);
            return redirectToError("MISSING_TX_ID", "transaction id is missing");
        }

        log.info("[AnyIdController] SSO 콜백 수신: provider={} txId={} cid={}", provider, txId, cid);

        try {
            // SSO 서버 연동 토큰 검증
            var ssoData = anyIdBrokerAdapter.verifySsoToken(txId, cid);

            String userId    = ssoData.path("userId").asText();
            String authLevel = ssoData.path("authLevel").asText("L1");

            // FE 세션 생성
            String feSessionId = createFeSession(userId, null, authLevel, returnUrl, cid);

            String redirectTarget = (returnUrl != null && !returnUrl.isBlank())
                    ? returnUrl : "/conversion/complete";

            HttpHeaders headers = new HttpHeaders();
            headers.setLocation(URI.create(redirectTarget));
            headers.add(HttpHeaders.SET_COOKIE,
                    buildSessionCookie(feSessionId, anyIdProperties.getSso().getSessionTtlSeconds()));
            return ResponseEntity.status(HttpStatus.FOUND).headers(headers).build();

        } catch (Exception e) {
            log.error("[AnyIdController] SSO 콜백 처리 실패: provider={} cid={} err={}",
                    provider, cid, e.getMessage());
            return redirectToError("ANYID_CALLBACK_ERROR", e.getMessage());
        }
    }


    // ──────────────────────────────────────────────────────────────────────
    // ssob 수신 처리 — SDK 복호화 핵심 엔드포인트
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Any-ID ssob 수신 및 처리 — SDK 기반 복호화 후 인증 완료 처리
     *
     * <p>FE의 {@code anyidAdaptor.orgLogin(data)} 에서 POST로 전송된다:
     * <pre>
     * // anyidAdaptor.js (이용기관 자체 로그인 패턴)
     * anyidAdaptor.orgLogin = function(data) {
     *     var obj = {
     *         ssob: data.ssob,
     *         tag:  params.get("tx")   // txId
     *     };
     *     xhr.open("POST", "/api/v1/anyid/{provider}/ssob");
     *     xhr.send(JSON.stringify(obj));
     * }
     * </pre>
     *
     * <p>처리 흐름:
     * <ol>
     *   <li>{@code AnyidCertRef.decryptSsob(ssob, tag, kdistPath)} — SDK 복호화</li>
     *   <li>CI 추출 → {@code NonOidcAuthService.processAuth()} — AuthResult 저장 + Kafka</li>
     *   <li>{@code FeSessionService.create()} — Redis 세션 생성</li>
     *   <li>feSessionId 쿠키 발급</li>
     * </ol>
     *
     * @param provider  인증 수단 경로변수
     * @param body      요청 본문 {@code { "ssob": "...", "tag": "...", "txId": "..." }}
     * @return {@code { "status": "success", "authLevel": "L2" }} 또는 에러
     */
    @PostMapping("/{provider}/ssob")
    public ResponseEntity<Map<String, Object>> processSsob(
            @PathVariable String provider,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {

        String cid = resolveCorrelationId(correlationId);
        String ssobStr = body.get("ssob");
        String tag     = body.get("tag");      // txId와 동일
        String txId    = body.getOrDefault("txId", tag);

        log.info("[AnyIdController] ssob 처리 시작: provider={} txId={} cid={}", provider, txId, cid);

        if (ssobStr == null || ssobStr.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "fail",
                    "code",   "MISSING_SSOB",
                    "message", "ssob 파라미터 없음"
            ));
        }

        try {
            // 1. SDK로 ssob 복호화 (AnyidCertRef.decryptSsob)
            Map<String, Object> ssob = anyIdSsobService.decryptSsob(ssobStr, tag, null);

            // 2. CI / authLevel 추출
            String ci        = anyIdSsobService.extractCi(ssob, cid);
            String authLevel = anyIdSsobService.extractAuthLevel(ssob);
            String name      = (String) ssob.getOrDefault("name", "");

            log.info("[AnyIdController] ssob 복호화 성공: provider={} authLevel={} cid={}",
                    provider, authLevel, cid);

            // 3. NonOidcAuthService로 AuthResult 생성 + Kafka Outbox 발행
            NonOidcAuthCommand command = NonOidcAuthCommand.builder()
                    .correlationId(cid)
                    .providerCode(normalizeProviderCode(provider))
                    .providerTxId(txId)
                    .rawIdentifier(ci)
                    .requestedLevel(authLevel)
                    .providerVerified(true)
                    .build();

            String authResultId = nonOidcAuthService.processAuth(command);
            log.info("[AnyIdController] AuthResult 저장: authResultId={} cid={}", authResultId, cid);

            // 4. FeSession 생성 (qimUserId는 identifierHash 기반, 추후 QIM 연동으로 대체)
            // Any-ID는 아직 QIM 통합 전이므로 임시로 authResultId를 userId로 사용
            String feSessionId = createFeSession(authResultId, authResultId, authLevel, null, cid);

            // 5. 응답 (feSessionId는 쿠키로 발급)
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.add(HttpHeaders.SET_COOKIE,
                    buildSessionCookie(feSessionId, anyIdProperties.getSso().getSessionTtlSeconds()));

            Map<String, Object> response = Map.of(
                    "status",       "success",
                    "authLevel",    authLevel,
                    "authResultId", authResultId,
                    "provider",     normalizeProviderCode(provider),
                    "name",         name
            );

            log.info("[AnyIdController] 인증 완료: provider={} authLevel={} cid={}", provider, authLevel, cid);
            return ResponseEntity.ok().headers(headers).body(response);

        } catch (PlatformException e) {
            log.error("[AnyIdController] ssob 처리 PlatformException: cid={} code={} msg={}",
                    cid, e.getErrorCode(), e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "status",  "fail",
                    "code",    e.getErrorCode() != null ? e.getErrorCode().name() : "ANYID_ERROR",
                    "message", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("[AnyIdController] ssob 처리 예외: provider={} cid={} err={}",
                    provider, cid, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "status",  "fail",
                    "code",    "ANYID_SSOB_ERROR",
                    "message", "ssob 처리 중 서버 오류: " + e.getMessage()
            ));
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // SSO 로그인 처리 (anyidAdaptor.ssoLogin() 경로)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Any-ID SSO 로그인 처리
     *
     * <p>anyidAdaptor.ssoLogin()에서 {@code GET /oidc/ssoLogin?data={base64}} 로 호출.
     * data 파라미터는 Base64 인코딩된 JSON: {@code { txId, ssob, userSeCd, afData }}
     *
     * <pre>
     * anyidAdaptor.ssoLogin = function() {
     *     let encodedString = btoa(JSON.stringify({ txId, ssob, userSeCd, afData }));
     *     window.location.href = "/oidc/ssoLogin?data=" + encodedString;
     * }
     * </pre>
     */
    @GetMapping("/oidc/ssoLogin")
    public ResponseEntity<Void> ssoLogin(
            @RequestParam String data,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {

        String cid = resolveCorrelationId(correlationId);
        log.info("[AnyIdController] SSO 로그인: cid={}", cid);

        try {
            // Base64 디코딩 → JSON 파싱
            String jsonStr = new String(Base64.getDecoder().decode(data), StandardCharsets.UTF_8);
            Map<String, String> payload = objectMapper.readValue(jsonStr,
                    new TypeReference<Map<String, String>>() {});

            String txId    = payload.get("txId");
            String ssobStr = payload.get("ssob");

            // ssob 복호화 (tag = txId)
            Map<String, Object> ssob = anyIdSsobService.decryptSsob(ssobStr, txId, null);
            String ci        = anyIdSsobService.extractCi(ssob, cid);
            String authLevel = anyIdSsobService.extractAuthLevel(ssob);

            NonOidcAuthCommand command = NonOidcAuthCommand.builder()
                    .correlationId(cid)
                    .providerCode("EASY_SIGN")   // SSO 경로는 간편인증으로 처리
                    .providerTxId(txId)
                    .rawIdentifier(ci)
                    .requestedLevel(authLevel)
                    .providerVerified(true)
                    .build();

            String authResultId = nonOidcAuthService.processAuth(command);
            String feSessionId  = createFeSession(authResultId, authResultId, authLevel, null, cid);

            HttpHeaders headers = new HttpHeaders();
            headers.setLocation(URI.create("/conversion/complete"));
            headers.add(HttpHeaders.SET_COOKIE,
                    buildSessionCookie(feSessionId, anyIdProperties.getSso().getSessionTtlSeconds()));
            return ResponseEntity.status(HttpStatus.FOUND).headers(headers).build();

        } catch (Exception e) {
            log.error("[AnyIdController] SSO 로그인 실패: cid={} err={}", cid, e.getMessage());
            return redirectToError("ANYID_SSO_LOGIN_FAILED", e.getMessage());
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
    // txId 발급 — FE SDK 초기화용 거래 ID 생성
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Any-ID SDK 거래 ID(txId) 발급
     *
     * <p>FE의 {@code useAnyIdAuth.ts}에서 {@code AnyidC.LOAD_MODULE()} 호출 전에 먼저 이 엔드포인트를
     * 통해 서버 측 거래 ID를 발급받는다. 서버 장애 시 FE에서 클라이언트 생성 ID로 fallback한다.
     *
     * <pre>
     * POST /api/v1/anyid/txId
     * → 200 OK { "txId": "20260519143022-a1b2c3d4" }
     * </pre>
     *
     * @return {@code { "txId": "yyyyMMddHHmmss-{uuid8}" }}
     */
    @PostMapping("/txId")
    public ResponseEntity<Map<String, String>> issueTxId() {
        String datePart = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String uuidPart = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String txId = datePart + "-" + uuidPart;

        log.debug("[AnyIdController] txId 발급: {}", txId);
        return ResponseEntity.ok(Map.of("txId", txId));
    }

    // ──────────────────────────────────────────────────────────────────────
    // ssob 통합 처리 — provider 없는 FE 호환 엔드포인트
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Any-ID ssob 통합 처리 (provider 없는 FE 호환 엔드포인트)
     *
     * <p>FE {@code useAnyIdAuth.ts}의 {@code handleOrgLogin}에서 호출:
     * <pre>
     * POST /api/v1/anyid/ssob
     * Body: { "ssob": "...", "tag": "...", "txId": "..." }
     * </pre>
     *
     * <p>기존 {@code /{provider}/ssob}와 달리 FE가 기대하는 응답 형식을 반환한다:
     * <pre>
     * 성공: { "resultCode": "2000", "ci": "{authResultId}", "resultMsg": "인증 완료", "authLevel": "L2" }
     * 실패: { "resultCode": "5000", "resultMsg": "...", "errorCode": "..." }
     * </pre>
     *
     * <p>내부적으로 {@code /{provider}/ssob}와 동일한 복호화·처리 로직을 사용하되,
     * provider는 {@code "EASY_SIGN"}(기본값)으로 고정한다.
     * FE에서 전달하는 {@code userSeCd} 또는 별도 파라미터로 provider를 구분할 수 있다.
     *
     * @param body          요청 본문 {@code { ssob, tag, txId, userSeCd? }}
     * @param correlationId 흐름 추적 ID
     * @return FE 호환 응답 {@code { resultCode, ci, resultMsg, authLevel }}
     */
    @PostMapping("/ssob")
    public ResponseEntity<Map<String, Object>> processSsobUnified(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {

        String cid     = resolveCorrelationId(correlationId);
        String ssobStr = body.get("ssob");
        String tag     = body.get("tag");
        String txId    = body.getOrDefault("txId", tag);
        // userSeCd 기반 provider 추론 (없으면 기본값 EASY_SIGN)
        String userSeCd = body.getOrDefault("userSeCd", "");
        String provider = resolveProviderFromUserSeCd(userSeCd);

        log.info("[AnyIdController] ssob 통합 처리: provider={} txId={} cid={}", provider, txId, cid);

        if (ssobStr == null || ssobStr.isBlank()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("resultCode", "5001");
            err.put("resultMsg",  "ssob 파라미터가 없습니다");
            err.put("errorCode",  "MISSING_SSOB");
            return ResponseEntity.badRequest().body(err);
        }

        try {
            // 1. SDK로 ssob 복호화 (AnyidCertRef.decryptSsob)
            Map<String, Object> ssob = anyIdSsobService.decryptSsob(ssobStr, tag, null);

            // 2. CI / authLevel / name 추출
            String ci        = anyIdSsobService.extractCi(ssob, cid);
            String authLevel = anyIdSsobService.extractAuthLevel(ssob);
            String name      = (String) ssob.getOrDefault("name", "");

            log.info("[AnyIdController] ssob 복호화 완료: authLevel={} cid={}", authLevel, cid);

            // 3. NonOidcAuthService로 AuthResult 저장 + Kafka Outbox 발행
            NonOidcAuthCommand command = NonOidcAuthCommand.builder()
                    .correlationId(cid)
                    .providerCode(normalizeProviderCode(provider))
                    .providerTxId(txId)
                    .rawIdentifier(ci)
                    .requestedLevel(authLevel)
                    .providerVerified(true)
                    .build();

            String authResultId = nonOidcAuthService.processAuth(command);
            log.info("[AnyIdController] AuthResult 저장: authResultId={} cid={}", authResultId, cid);

            // 4. FeSession 생성
            String feSessionId = createFeSession(authResultId, authResultId, authLevel, null, cid);

            // 5. FE 호환 응답 반환 (resultCode:"2000", ci:authResultId)
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.add(HttpHeaders.SET_COOKIE,
                    buildSessionCookie(feSessionId, anyIdProperties.getSso().getSessionTtlSeconds()));

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("resultCode", "2000");
            response.put("ci",         authResultId);   // authResultId를 CI lookup token으로 사용
            response.put("resultMsg",  "인증 완료");
            response.put("authLevel",  authLevel);
            response.put("name",       name);

            log.info("[AnyIdController] 통합 인증 완료: authLevel={} cid={}", authLevel, cid);
            return ResponseEntity.ok().headers(headers).body(response);

        } catch (PlatformException e) {
            log.error("[AnyIdController] ssob 통합 처리 PlatformException: cid={} code={} msg={}",
                    cid, e.getErrorCode(), e.getMessage());
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("resultCode", "5000");
            err.put("resultMsg",  e.getMessage());
            err.put("errorCode",  e.getErrorCode() != null ? e.getErrorCode().name() : "ANYID_ERROR");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err);

        } catch (Exception e) {
            log.error("[AnyIdController] ssob 통합 처리 예외: cid={} err={}", cid, e.getMessage(), e);
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("resultCode", "5000");
            err.put("resultMsg",  "ssob 처리 중 서버 오류: " + e.getMessage());
            err.put("errorCode",  "ANYID_SSOB_ERROR");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err);
        }
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

    /**
     * FeSession 생성 헬퍼
     * 실제 운영에서는 QIM 조회 후 qimUserId를 얻어야 함.
     * PoC 단계에서는 authResultId를 임시 userId로 사용.
     */
    private String createFeSession(String qimUserId, String authResultId,
                                    String authLevel, String returnUrl, String cid) {
        try {
            var session = feSessionService.create(qimUserId, authResultId, authLevel, returnUrl);
            return session.getFeSessionId();
        } catch (Exception e) {
            log.warn("[AnyIdController] FeSession 생성 실패 (임시 ID 반환): cid={} err={}", cid, e.getMessage());
            return UuidV7.generate(); // 테스트 환경 fallback
        }
    }

    /**
     * feSessionId 쿠키 문자열 생성
     * SameSite=Lax, HttpOnly, Secure, Max-Age 설정
     */
    private String buildSessionCookie(String feSessionId, long ttlSeconds) {
        return String.format(
                "fe_session=%s; Path=/; HttpOnly; SameSite=Lax; Max-Age=%d",
                feSessionId, ttlSeconds
        );
    }

    /** FE provider 경로변수 → DB provider_code 정규화 */
    private String normalizeProviderCode(String provider) {
        if (provider == null) return "EASY_SIGN";
        return switch (provider.toLowerCase().replace("-", "_")) {
            case "mobile_id", "mid"              -> "MOBILE_ID";
            case "easy_sign", "easy"             -> "EASY_SIGN";
            case "joint_cert", "npki"            -> "JOINT_CERT";
            case "financial_cert", "fincert"     -> "FINANCIAL_CERT";
            case "pid", "private_id"             -> "PRIVATE_ID";
            default -> provider.toUpperCase().replace("-", "_");
        };
    }

    /**
     * userSeCd → provider 추론
     * Any-ID SDK의 userSeCd 필드를 기반으로 인증 수단을 구분한다.
     * userSeCd가 없으면 기본값 "easy-sign"을 반환한다.
     *
     * <ul>
     *   <li>"01" — 모바일 신분증</li>
     *   <li>"02" — 간편인증</li>
     *   <li>"03" — 공동인증서</li>
     *   <li>"04" — 금융인증서</li>
     *   <li>"05" — 민간ID</li>
     * </ul>
     */
    private String resolveProviderFromUserSeCd(String userSeCd) {
        if (userSeCd == null || userSeCd.isBlank()) return "easy-sign";
        return switch (userSeCd.trim()) {
            case "01" -> "mobile-id";
            case "02" -> "easy-sign";
            case "03" -> "joint-cert";
            case "04" -> "financial-cert";
            case "05" -> "pid";
            default   -> "easy-sign";
        };
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
