package io.github.hipstermin.idem.plugin.niceoacx;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.spi.identity.IdentityVerificationException;
import io.github.hipstermin.idem.plugin.niceoacx.NiceAuthSessionStore.NiceAuthSession;
import io.github.hipstermin.idem.plugin.niceoacx.NiceTokenStore.NiceTokenSnapshot;
import io.github.hipstermin.idem.plugin.niceoacx.dto.NiceResultApiResponse;
import io.github.hipstermin.idem.plugin.niceoacx.dto.NiceTokenApiResponse;
import io.github.hipstermin.idem.plugin.niceoacx.dto.NiceUrlApiResponse;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

/**
 * NICE 휴대폰 본인인증 — 토큰 관리·URL 발급·결과 복호화 ({@link NicePhoneGateway} 구현, S5a).
 *
 * <p>종전 idem-hub {@code NiceAuthService} 에서 옮겨 오며 두 가지를 뺐다: registry 등록(코어의
 * {@code SubjectRegistrationService} 가 SPI 결과로 수행)과 감사(코어 컨트롤러가 제공자 코드 단위로 기록).
 * 실패는 {@link IdentityVerificationException}(reasonCode = 종전 결과 코드) 로 던진다.
 *
 * <pre>
 * start:  POST /auth/token(Basic, 캐시·분산락) → POST /auth/url → Redis 세션(requestNo → transactionId)
 * result: Redis 세션 조회 → POST /auth/result → PBKDF2 키 파생 → HMAC 검증 → AES-GCM 복호화
 * </pre>
 */
@Slf4j
public class NicePhoneService implements NicePhoneGateway {

    static final String CODE = NicePhoneIdentityVerificationProvider.CODE;
    private static final String RESULT_OK = "0000";
    private static final String NICE_TOKEN_LOCK_KEY = "ido:lock:nice-token-refresh";
    private static final long LOCK_WAIT_SECONDS = 3L;
    private static final long LOCK_LEASE_SECONDS = 10L;

    private final NiceApiClient niceApiClient;
    private final NiceTokenStore tokenStore;
    private final NiceAuthSessionStore sessionStore;
    private final ObjectMapper objectMapper;
    private final String defaultReturnUrl;
    private final RedissonClient redissonClient;

    public NicePhoneService(NiceApiClient niceApiClient, NiceTokenStore tokenStore, NiceAuthSessionStore sessionStore,
                            ObjectMapper objectMapper, NiceProperties props, RedissonClient redissonClient) {
        this.niceApiClient = niceApiClient;
        this.tokenStore = tokenStore;
        this.sessionStore = sessionStore;
        this.objectMapper = objectMapper;
        this.defaultReturnUrl = props.getReturnUrl();
        this.redissonClient = redissonClient;
    }

    @Override
    public Started start(String returnUrl) {
        log.info("[NICE] 휴대폰 인증 URL 요청: returnUrl={}", returnUrl);
        NiceTokenSnapshot token = ensureAccessToken();
        String requestNo = makeRequestNo();
        String effectiveReturnUrl = (returnUrl != null && !returnUrl.isBlank()) ? returnUrl : defaultReturnUrl;

        NiceUrlApiResponse urlResponse = niceApiClient.requestAuthUrl(token.accessToken(), requestNo, effectiveReturnUrl);
        if (urlResponse == null || !RESULT_OK.equals(urlResponse.getResultCode())) {
            String msg = urlResponse != null ? urlResponse.getResultMessage() : "응답 없음";
            log.error("[NICE] URL 발급 실패: resultCode={}, message={}", urlResponse != null ? urlResponse.getResultCode() : "null", msg);
            throw new IdentityVerificationException(CODE, "5001", "NICE 인증 URL 발급 실패: " + msg);
        }
        // NICE 응답의 request_no 를 세션 키로 사용 (NICE 서버가 반환한 값 우선)
        String responseRequestNo = urlResponse.getRequestNo() != null ? urlResponse.getRequestNo() : requestNo;
        sessionStore.save(responseRequestNo, urlResponse.getTransactionId());
        log.info("[NICE] URL 발급 성공: requestNo={}, transactionId={}", responseRequestNo, urlResponse.getTransactionId());
        return new Started(responseRequestNo, urlResponse.getAuthUrl());
    }

    @Override
    public Result result(String webTransactionId, String requestNo) {
        log.info("[NICE] 인증 결과 요청: webTransactionId={}, requestNo={}", webTransactionId, requestNo);
        if (requestNo == null || requestNo.isBlank()) {
            throw new IdentityVerificationException(CODE, "4000", "request_no 가 필요합니다");
        }
        NiceTokenSnapshot token = ensureAccessToken();
        NiceAuthSession session = sessionStore.find(requestNo);
        if (session == null) {
            log.error("[NICE] 세션 없음 또는 만료: requestNo={}", requestNo);
            throw new IdentityVerificationException(CODE, "4000", "인증 세션 정보를 찾을 수 없습니다 (만료 또는 잘못된 request_no)");
        }
        NiceResultApiResponse result = niceApiClient.requestAuthResult(
                token.accessToken(), webTransactionId, session.transactionId(), session.requestNo());
        if (result == null || !RESULT_OK.equals(result.getResultCode())) {
            String msg = result != null ? result.getResultMessage() : "응답 없음";
            log.error("[NICE] 결과 조회 실패: resultCode={}, message={}", result != null ? result.getResultCode() : "null", msg);
            throw new IdentityVerificationException(CODE, "5002", "NICE 인증 결과 조회 실패: " + msg);
        }
        Map<String, Object> resultMap;
        try {
            resultMap = decryptAndVerify(result, token, session.transactionId());
        } catch (DataIntegrityException e) {
            log.error("[NICE] HMAC 무결성 검증 실패 — 데이터 위변조 의심");
            throw new IdentityVerificationException(CODE, "5003", "데이터 무결성 검증 실패");
        } catch (Exception e) {
            log.error("[NICE] 결과 복호화 중 오류", e);
            throw new IdentityVerificationException(CODE, "5000", "NICE 인증 결과 처리 오류: " + e.getMessage());
        }
        // 결과 조회 성공 → 일회성 세션 삭제
        sessionStore.remove(session.requestNo());
        log.info("[NICE] 인증 결과 복호화 성공: nationalInfo={}", resultMap.get("national_info"));
        return new Result(str(resultMap.get("ci")), str(resultMap.get("di")), str(resultMap.get("name")),
                str(resultMap.get("birthdate")), str(resultMap.get("gender")), str(resultMap.get("national_info")),
                str(resultMap.get("mobile_no")), str(resultMap.get("mobile_co")));
    }

    /**
     * NICE Access Token 유효성 확인 및 자동 재발급 (Redisson 분산 락 — 다중 Pod 동시 갱신 방지).
     * 빠른 경로(캐시 유효) → tryLock(3s) → Double-Checked → 발급 → finally unlock.
     */
    NiceTokenSnapshot ensureAccessToken() {
        if (tokenStore.isValid()) {
            return tokenStore.get();
        }
        RLock lock = redissonClient.getLock(NICE_TOKEN_LOCK_KEY);
        boolean locked = false;
        try {
            locked = lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            if (!locked) {
                log.warn("[NICE] 분산 락 획득 실패 (다른 Pod 갱신 중 추정) — 캐시 재확인");
                try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                NiceTokenSnapshot cached = tokenStore.get();
                if (cached != null && tokenStore.isValid()) return cached;
                throw new IdentityVerificationException(CODE, "5000", "NICE 토큰 갱신 락 획득 실패 및 유효 토큰 없음");
            }
            if (tokenStore.isValid()) {
                return tokenStore.get();
            }
            log.info("[NICE] Access Token 신규 발급 요청 (분산 락 보유)");
            NiceTokenApiResponse tokenResponse = niceApiClient.fetchAccessToken(makeRequestNo());
            if (tokenResponse == null || !RESULT_OK.equals(tokenResponse.getResultCode())) {
                String msg = tokenResponse != null ? tokenResponse.getResultMessage() : "응답 없음";
                throw new IdentityVerificationException(CODE, "5000", "NICE Access Token 발급 실패: " + msg);
            }
            tokenStore.save(tokenResponse.getAccessToken(), tokenResponse.getExpiresIn(),
                    tokenResponse.getTicket(), tokenResponse.getIterators());
            log.info("[NICE] Access Token 발급 성공: expiresIn={}", tokenResponse.getExpiresIn());
            return tokenStore.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IdentityVerificationException(CODE, "5000", "NICE 토큰 갱신 중 인터럽트");
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /** PBKDF2 키 파생 → HMAC-SHA256 무결성 검증 → AES-256-GCM 복호화 → JSON Map. */
    private Map<String, Object> decryptAndVerify(NiceResultApiResponse response, NiceTokenSnapshot token,
                                                 String transactionId) throws Exception {
        String keyString = NiceCryptoUtil.deriveKey(token.ticket(), transactionId, token.iterators());
        byte[] aesKey = NiceCryptoUtil.extractAesKey(keyString);
        String hmacKey = NiceCryptoUtil.extractHmacKey(keyString);
        String calculated = NiceCryptoUtil.hmacSha256Base64Url(response.getEncData(), hmacKey);
        if (!calculated.equals(response.getIntegrityValue())) {
            throw new DataIntegrityException();
        }
        String decrypted = NiceCryptoUtil.aesGcmDecrypt(aesKey, response.getEncData());
        return objectMapper.readValue(decrypted, new TypeReference<>() {});
    }

    /** NICE 요청 번호: {@code REQ_yyyyMMddHHmmss + UUID 12자리}. */
    private String makeRequestNo() {
        String timestamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
        String uniqueId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return "REQ_" + timestamp + uniqueId;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    static class DataIntegrityException extends RuntimeException {
        DataIntegrityException() {
            super("NICE 응답 데이터 무결성 검증 실패");
        }
    }
}
