package kr.go.smes.ido.auth.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.go.smes.ido.auth.client.NiceApiClient;
import kr.go.smes.ido.auth.config.AuthProperties;
import kr.go.smes.ido.auth.dto.NicePhoneAuthResultRequest;
import kr.go.smes.ido.auth.dto.NicePhoneAuthResultResponse;
import kr.go.smes.ido.auth.dto.NicePhoneAuthUrlResponse;
import kr.go.smes.ido.auth.dto.nice.NiceResultApiResponse;
import kr.go.smes.ido.auth.dto.nice.NiceTokenApiResponse;
import kr.go.smes.ido.auth.dto.nice.NiceUrlApiResponse;
import kr.go.smes.ido.auth.store.NiceAuthSessionStore;
import kr.go.smes.ido.auth.store.NiceAuthSessionStore.NiceAuthSession;
import kr.go.smes.ido.auth.store.NiceTokenStore;
import kr.go.smes.ido.auth.store.NiceTokenStore.NiceTokenSnapshot;
import kr.go.smes.ido.auth.util.NiceCryptoUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
 * NICE 휴대폰 본인인증 비즈니스 로직 서비스
 *
 * <p>NICE IDO 통합인증 표준 API와의 연동 전체 플로우를 담당:
 * <ol>
 *   <li>Access Token 관리 — Redis 캐시 기반, 만료 60초 전 재발급</li>
 *   <li>인증 URL 발급 — NICE 표준창 URL 생성 및 세션 저장</li>
 *   <li>인증 결과 조회 — 암호화 데이터 복호화 및 무결성 검증</li>
 * </ol>
 *
 * <p><b>FE API 계약:</b>
 * <ul>
 *   <li>{@code GET /api/v1/auth/nice/phone/url?returnUrl=...}</li>
 *   <li>{@code POST /api/v1/auth/nice/phone/result}</li>
 * </ul>
 *
 * <p><b>NICE 인증 전체 플로우:</b>
 * <pre>
 * FE                        ido                         NICE 서버
 * |                          |                              |
 * | GET /nice/phone/url      |                              |
 * |------------------------->|                              |
 * |                          | POST /auth/token (Basic)     |
 * |                          |----------------------------->|
 * |                          | ← {accessToken, ticket, ...} |
 * |                          |                              |
 * |                          | POST /auth/url               |
 * |                          |----------------------------->|
 * |                          | ← {authUrl, transactionId}  |
 * |                          | Redis: save(requestNo, txId) |
 * | ← {authUrl, requestNo}  |                              |
 * |                          |                              |
 * | [팝업 오픈 → 사용자 인증]  |                              |
 * |                          |                              |
 * | postMessage(web_txn_id)  |                              |
 * |                          |                              |
 * | POST /nice/phone/result  |                              |
 * |------------------------->|                              |
 * |                          | Redis: find(requestNo)→txId  |
 * |                          | POST /auth/result            |
 * |                          |----------------------------->|
 * |                          | ← {encData, integrityValue} |
 * |                          | PBKDF2 + HMAC 검증           |
 * |                          | AES-GCM 복호화               |
 * | ← {name, birthdate, di...}|                             |
 * </pre>
 *
 * <p><b>보안 정책 (Q3=B):</b>
 * CI(연계정보)는 FE에 반환하지 않음. DI는 반환.
 * CI는 복호화 결과에서 추출 가능하지만 FE 응답 DTO({@code NicePhoneAuthResultResponse})에
 * ci 필드가 없으므로 자연스럽게 누락됨.
 *
 * <p><b>스레드 안전성:</b>
 * {@code ensureAccessToken()}은 {@code synchronized}로 보호.
 * Redis 연산은 원자적이지만, 멀티 Pod 환경에서 토큰 중복 발급 가능성 존재 — 허용 수준.
 *
 * @see NiceApiClient
 * @see NiceTokenStore
 * @see NiceAuthSessionStore
 * @see NiceCryptoUtil
 */
@Slf4j
@Service
public class NiceAuthService {

    /** NICE 성공 결과 코드 */
    private static final String RESULT_OK = "0000";

    private final NiceApiClient niceApiClient;
    private final NiceTokenStore tokenStore;
    private final NiceAuthSessionStore sessionStore;
    private final ObjectMapper objectMapper;
    private final String defaultReturnUrl;

    public NiceAuthService(NiceApiClient niceApiClient,
                            NiceTokenStore tokenStore,
                            NiceAuthSessionStore sessionStore,
                            ObjectMapper objectMapper,
                            AuthProperties props) {
        this.niceApiClient = niceApiClient;
        this.tokenStore = tokenStore;
        this.sessionStore = sessionStore;
        this.objectMapper = objectMapper;
        this.defaultReturnUrl = props.nice().returnUrl();
    }

    /**
     * NICE 휴대폰 본인인증 표준창 URL 발급
     *
     * <p>내부적으로 Access Token 발급(캐시 활용) 후 NICE API를 통해 표준창 URL을 생성.
     * 발급된 transactionId는 Redis에 저장하여 이후 결과 조회 시 사용.
     *
     * <p><b>returnUrl 우선순위:</b>
     * <ol>
     *   <li>파라미터로 전달된 returnUrl (FE가 명시적으로 지정)</li>
     *   <li>설정값 {@code ido.auth.nice.return-url} (기본값)</li>
     * </ol>
     *
     * @param returnUrl 인증 완료 후 리다이렉트 URL (null/blank이면 기본값 사용)
     * @return NICE 인증 URL 응답 (authUrl, requestNo)
     */
    public NicePhoneAuthUrlResponse getNicePhoneAuthUrl(String returnUrl) {
        log.info("[NICE] 휴대폰 인증 URL 요청: returnUrl={}", returnUrl);
        try {
            NiceTokenSnapshot token = ensureAccessToken();
            String requestNo = makeRequestNo();
            String effectiveReturnUrl = (returnUrl != null && !returnUrl.isBlank()) ? returnUrl : defaultReturnUrl;

            NiceUrlApiResponse urlResponse = niceApiClient.requestAuthUrl(
                    token.accessToken(), requestNo, effectiveReturnUrl);

            if (urlResponse == null || !RESULT_OK.equals(urlResponse.getResultCode())) {
                String msg = urlResponse != null ? urlResponse.getResultMessage() : "응답 없음";
                log.error("[NICE] URL 발급 실패: resultCode={}, message={}",
                        urlResponse != null ? urlResponse.getResultCode() : "null", msg);
                return NicePhoneAuthUrlResponse.builder()
                        .resultCode("5001")
                        .resultMsg("NICE 인증 URL 발급 실패: " + msg)
                        .build();
            }

            // NICE 응답의 request_no를 세션 키로 사용 (NICE 서버가 반환한 값 우선)
            String responseRequestNo = urlResponse.getRequestNo() != null
                    ? urlResponse.getRequestNo() : requestNo;
            sessionStore.save(responseRequestNo, urlResponse.getTransactionId());

            log.info("[NICE] URL 발급 성공: requestNo={}, transactionId={}",
                    responseRequestNo, urlResponse.getTransactionId());

            return NicePhoneAuthUrlResponse.builder()
                    .resultCode("2000")
                    .resultMsg("성공")
                    .authUrl(urlResponse.getAuthUrl())
                    .requestNo(responseRequestNo)
                    .build();

        } catch (Exception e) {
            log.error("[NICE] URL 생성 중 오류 발생", e);
            return NicePhoneAuthUrlResponse.builder()
                    .resultCode("5000")
                    .resultMsg("NICE 인증 URL 생성 오류: " + e.getMessage())
                    .build();
        }
    }

    /**
     * NICE 휴대폰 본인인증 결과 조회 및 복호화
     *
     * <p>NICE 팝업 완료 후 FE가 전달한 web_transaction_id와 request_no로 인증 결과를 조회.
     * Redis에서 transactionId를 찾아 NICE API를 호출하고, 결과를 AES-GCM으로 복호화.
     * 복호화 결과에서 개인 정보(name, birthdate 등)를 추출하여 FE에 반환.
     *
     * <p><b>CI 처리 (Q3=B):</b>
     * 복호화 결과에 CI가 포함되어 있지만 FE 응답 DTO에는 ci 필드가 없으므로 자동 제외.
     * TODO(S7-T6): IM API 연동 후 이 메서드에서 CI를 추출하여 IM API에 등록 예정.
     *
     * @param request web_transaction_id, request_no 포함 요청
     * @return 본인인증 결과 응답 (name, birthdate, gender, nationalInfo, di, mobileCo, mobileNo)
     */
    public NicePhoneAuthResultResponse getNicePhoneAuthResult(NicePhoneAuthResultRequest request) {
        String webTransactionId = request.getWebTransactionId();
        String requestNo = request.getRequestNo();
        log.info("[NICE] 인증 결과 요청: webTransactionId={}, requestNo={}", webTransactionId, requestNo);

        try {
            if (requestNo == null || requestNo.isBlank()) {
                log.error("[NICE] request_no 누락");
                return NicePhoneAuthResultResponse.builder()
                        .resultCode("4000")
                        .resultMsg("request_no 가 필요합니다")
                        .build();
            }

            NiceTokenSnapshot token = ensureAccessToken();

            NiceAuthSession session = sessionStore.find(requestNo);
            if (session == null) {
                log.error("[NICE] 세션 없음 또는 만료: requestNo={}", requestNo);
                return NicePhoneAuthResultResponse.builder()
                        .resultCode("4000")
                        .resultMsg("인증 세션 정보를 찾을 수 없습니다 (만료 또는 잘못된 request_no)")
                        .build();
            }

            NiceResultApiResponse result = niceApiClient.requestAuthResult(
                    token.accessToken(), webTransactionId, session.transactionId(), session.requestNo());

            if (result == null || !RESULT_OK.equals(result.getResultCode())) {
                String msg = result != null ? result.getResultMessage() : "응답 없음";
                log.error("[NICE] 결과 조회 실패: resultCode={}, message={}",
                        result != null ? result.getResultCode() : "null", msg);
                return NicePhoneAuthResultResponse.builder()
                        .resultCode("5002")
                        .resultMsg("NICE 인증 결과 조회 실패: " + msg)
                        .build();
            }

            // HMAC 검증 + AES-GCM 복호화
            Map<String, Object> resultMap = decryptAndVerify(result, token, session.transactionId());

            // 결과 조회 성공 → 일회성 세션 삭제
            sessionStore.remove(session.requestNo());

            // 복호화 결과에서 개인정보 추출
            // CI는 Q3=B 결정에 의해 FE 미반환 (resultMap.get("ci")는 내부에서만 사용 가능)
            String ciForInternalUse = (String) resultMap.get("ci");
            log.info("[NICE] 인증 결과 복호화 성공: name={}, nationalInfo={}",
                    resultMap.get("name"), resultMap.get("national_info"));
            // TODO(S7-T6): IM API 연동 후 아래 주석 해제하여 CI 저장 처리
            // if (ciForInternalUse != null && !ciForInternalUse.isBlank()) {
            //     authService.processCiRegistration(ciForInternalUse, resultMap);
            // }

            return NicePhoneAuthResultResponse.builder()
                    .resultCode("2000")
                    .resultMsg("성공")
                    .resultData(NicePhoneAuthResultResponse.ResultData.builder()
                            .name((String) resultMap.get("name"))
                            .birthdate((String) resultMap.get("birthdate"))
                            .gender((String) resultMap.get("gender"))
                            .nationalInfo((String) resultMap.get("national_info"))
                            // CI는 FE 미반환 (Q3=B) — resultData에 ci 필드 없음
                            .di((String) resultMap.get("di"))
                            .mobileCo((String) resultMap.get("mobile_co"))
                            .mobileNo((String) resultMap.get("mobile_no"))
                            .build())
                    .build();

        } catch (DataIntegrityException e) {
            log.error("[NICE] HMAC 무결성 검증 실패 — 데이터 위변조 의심");
            return NicePhoneAuthResultResponse.builder()
                    .resultCode("5003")
                    .resultMsg("데이터 무결성 검증 실패")
                    .build();
        } catch (Exception e) {
            log.error("[NICE] 결과 처리 중 오류 발생", e);
            return NicePhoneAuthResultResponse.builder()
                    .resultCode("5000")
                    .resultMsg("NICE 인증 결과 처리 오류: " + e.getMessage())
                    .build();
        }
    }

    /**
     * NICE Access Token 유효성 확인 및 자동 재발급
     *
     * <p>Redis에 유효한 토큰이 있으면 재사용. 없거나 만료(60초 전) 시 NICE API 호출하여 재발급.
     * {@code synchronized}로 동일 인스턴스 내 중복 발급 방지.
     * 다중 Pod 환경에서는 미세한 중복 발급 가능성 있으나 허용 가능한 수준.
     *
     * @return 현재 유효한 NICE Access Token 스냅샷
     * @throws IllegalStateException NICE 토큰 발급 실패
     */
    private synchronized NiceTokenSnapshot ensureAccessToken() {
        if (tokenStore.isValid()) {
            log.debug("[NICE] 캐시된 Access Token 사용");
            return tokenStore.get();
        }

        log.info("[NICE] Access Token 신규 발급 요청");
        NiceTokenApiResponse tokenResponse = niceApiClient.fetchAccessToken(makeRequestNo());

        if (tokenResponse == null || !RESULT_OK.equals(tokenResponse.getResultCode())) {
            String msg = tokenResponse != null ? tokenResponse.getResultMessage() : "응답 없음";
            throw new IllegalStateException("[NICE] Access Token 발급 실패: " + msg);
        }

        tokenStore.save(
                tokenResponse.getAccessToken(),
                tokenResponse.getExpiresIn(),
                tokenResponse.getTicket(),
                tokenResponse.getIterators()
        );
        log.info("[NICE] Access Token 발급 성공: expiresIn={}", tokenResponse.getExpiresIn());
        return tokenStore.get();
    }

    /**
     * NICE 인증 결과 HMAC 검증 및 AES-GCM 복호화
     *
     * <p>NICE 암호화 스펙에 따라:
     * <ol>
     *   <li>PBKDF2WithHmacSHA256으로 키 파생 (ticket + transactionId + iterators)</li>
     *   <li>HMAC-SHA256으로 무결성 검증 (integrityValue 비교)</li>
     *   <li>AES-256-GCM으로 encData 복호화</li>
     *   <li>JSON → Map 파싱</li>
     * </ol>
     *
     * @param response      NICE 결과 API 응답 (encData, integrityValue)
     * @param token         현재 유효한 Access Token 스냅샷 (ticket, iterators)
     * @param transactionId NICE URL 발급 시 수신한 트랜잭션 ID
     * @return 복호화된 인증 결과 Map (name, birthdate, gender, national_info, ci, di, mobile_co, mobile_no)
     * @throws DataIntegrityException HMAC 검증 실패 (데이터 위변조 의심)
     * @throws Exception              복호화 중 오류
     */
    private Map<String, Object> decryptAndVerify(NiceResultApiResponse response,
                                                   NiceTokenSnapshot token,
                                                   String transactionId) throws Exception {
        // 1. PBKDF2로 복합 키 파생
        String keyString = NiceCryptoUtil.deriveKey(token.ticket(), transactionId, token.iterators());

        // 2. 키 분리 (AES 키: 0~31자, HMAC 키: 48~79자)
        byte[] aesKey = NiceCryptoUtil.extractAesKey(keyString);
        String hmacKey = NiceCryptoUtil.extractHmacKey(keyString);

        // 3. HMAC-SHA256 무결성 검증
        String calculated = NiceCryptoUtil.hmacSha256Base64Url(response.getEncData(), hmacKey);
        if (!calculated.equals(response.getIntegrityValue())) {
            log.error("[NICE] HMAC 불일치! calculated={}, expected={}", calculated, response.getIntegrityValue());
            throw new DataIntegrityException();
        }

        // 4. AES-256-GCM 복호화
        String decrypted = NiceCryptoUtil.aesGcmDecrypt(aesKey, response.getEncData());

        // 5. JSON → Map 파싱
        return objectMapper.readValue(decrypted, new TypeReference<>() {});
    }

    /**
     * 요청 번호(request_no) 생성
     *
     * <p>NICE API가 요구하는 고유 요청 번호.
     * 형식: {@code REQ_yyyyMMddHHmmss + UUID 12자리}
     *
     * @return 고유 요청 번호 (예: REQ_20240510123456a1b2c3d4e5f6)
     */
    private String makeRequestNo() {
        String timestamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
        String uniqueId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return "REQ_" + timestamp + uniqueId;
    }

    /**
     * NICE HMAC 무결성 검증 실패 예외
     *
     * <p>integrityValue 불일치 시 발생. 데이터 위변조 또는 전송 오류 가능성.
     * 호출 측에서 5003 에러 코드로 변환하여 FE에 반환.
     */
    static class DataIntegrityException extends RuntimeException {
        DataIntegrityException() {
            super("NICE 응답 데이터 무결성 검증 실패");
        }
    }
}
