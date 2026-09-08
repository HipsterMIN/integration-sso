package io.github.hipstermin.idem.hub.auth.audit;

import io.github.hipstermin.idem.common.event.AuditLogEvent;
import io.github.hipstermin.idem.common.util.CorrelationIdHolder;
import io.github.hipstermin.idem.hub.audit.AuditLogPublisher;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 본인인증 감사 로그 서비스 (S9-T5)
 *
 * <p>NICE 휴대폰 본인인증, OACX 간편서명, CI 처리, 기업인증 콜백 등
 * auth 도메인 이벤트를 {@code platform.audit.log} 토픽에 발행한다.
 *
 * <p><b>PII 보호 원칙 (Q3=B):</b>
 * <ul>
 *   <li>CI(연계정보) 원본 포함 금지 — 해시값이나 requestNo만 기록</li>
 *   <li>이름, 생년월일, 전화번호 등 개인정보 포함 금지</li>
 *   <li>DI(중복확인정보)는 requestNo와 함께 correlationId로 추적 가능</li>
 * </ul>
 *
 * <p><b>감사 이벤트 목록:</b>
 * <pre>
 * AUTH_NICE_URL_ISSUED       — NICE 인증 URL 발급 성공
 * AUTH_NICE_URL_FAILED       — NICE 인증 URL 발급 실패
 * AUTH_NICE_RESULT_SUCCESS   — NICE 인증 결과 복호화 + Q-IM 등록 성공
 * AUTH_NICE_RESULT_FAILED    — NICE 인증 결과 처리 실패 (복호화 오류 / Q-IM 등록 실패)
 * AUTH_NICE_CI_CHECK_SUCCESS — CI 기반 회원 확인 성공
 * AUTH_NICE_CI_CHECK_FAILED  — CI 기반 회원 확인 실패
 * AUTH_OACX_ACCESS_INFO      — OACX 접근키 발급 성공
 * AUTH_OACX_EASYSIGN_SUCCESS — OACX 간편서명 복호화 성공
 * AUTH_OACX_EASYSIGN_FAILED  — OACX 간편서명 복호화 실패
 * AUTH_CALLBACK_SUCCESS      — 기업인증 콜백 처리 성공
 * AUTH_CALLBACK_FAILED       — 기업인증 콜백 처리 실패
 * </pre>
 *
 * @see AuditLogPublisher
 * @see io.github.hipstermin.idem.hub.auth.service.NiceAuthService
 * @see io.github.hipstermin.idem.hub.auth.service.AuthService
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthAuditService {

    // ── 감사 액션 상수 ─────────────────────────────────────────────────────
    public static final String ACTION_NICE_URL_ISSUED       = "AUTH_NICE_URL_ISSUED";
    public static final String ACTION_NICE_URL_FAILED       = "AUTH_NICE_URL_FAILED";
    public static final String ACTION_NICE_RESULT_SUCCESS   = "AUTH_NICE_RESULT_SUCCESS";
    public static final String ACTION_NICE_RESULT_FAILED    = "AUTH_NICE_RESULT_FAILED";
    public static final String ACTION_NICE_CI_CHECK_SUCCESS = "AUTH_NICE_CI_CHECK_SUCCESS";
    public static final String ACTION_NICE_CI_CHECK_FAILED  = "AUTH_NICE_CI_CHECK_FAILED";
    public static final String ACTION_OACX_ACCESS_INFO      = "AUTH_OACX_ACCESS_INFO";
    public static final String ACTION_OACX_EASYSIGN_SUCCESS = "AUTH_OACX_EASYSIGN_SUCCESS";
    public static final String ACTION_OACX_EASYSIGN_FAILED  = "AUTH_OACX_EASYSIGN_FAILED";
    public static final String ACTION_CALLBACK_SUCCESS      = "AUTH_CALLBACK_SUCCESS";
    public static final String ACTION_CALLBACK_FAILED       = "AUTH_CALLBACK_FAILED";

    private final AuditLogPublisher auditLogPublisher;

    // ─────────────────────────────────────────────────────────────────────────
    // NICE 인증 감사 이벤트
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * NICE 인증 URL 발급 감사 이벤트 발행
     *
     * @param requestNo   발급된 requestNo (PII 없음)
     * @param resultCode  결과 코드 (2000=성공, 그 외=실패)
     * @param failReason  실패 사유 (성공 시 null)
     */
    public void publishNiceUrlEvent(String requestNo, String resultCode, String failReason) {
        boolean success = "2000".equals(resultCode);
        String action = success ? ACTION_NICE_URL_ISSUED : ACTION_NICE_URL_FAILED;

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("requestNo", requestNo);
        metadata.put("resultCode", resultCode);
        if (failReason != null) metadata.put("failReason", failReason);

        auditLogPublisher.publish(
                AuditLogPublisher.AuditEntry.builder()
                        .eventCategory(AuditLogEvent.CATEGORY_AUTH)
                        .eventAction(action)
                        .actorType(AuditLogEvent.ACTOR_USER)
                        .resourceType("NICE_AUTH_SESSION")
                        .resourceId(requestNo)
                        .correlationId(CorrelationIdHolder.get())
                        .outcome(success ? AuditLogEvent.OUTCOME_SUCCESS : AuditLogEvent.OUTCOME_FAILURE)
                        .outcomeDetail(failReason)
                        .metadata(metadata)
                        .build()
        );
    }

    /**
     * NICE 인증 결과 처리 감사 이벤트 발행
     *
     * <p>PII 보호: di는 포함하지 않음 (requestNo로 추적 가능).
     * nationalInfo(내외국인)는 비PII이므로 포함.
     *
     * @param requestNo       요청 번호
     * @param webTransactionId 웹 트랜잭션 ID (NICE 측 식별자)
     * @param resultCode      결과 코드
     * @param qimUserId       Q-IM 등록된 사용자 ID (null이면 미등록)
     * @param isNewUser       신규 등록 여부
     * @param failReason      실패 사유 (성공 시 null)
     */
    public void publishNiceResultEvent(String requestNo, String webTransactionId,
                                        String resultCode, String qimUserId,
                                        Boolean isNewUser, String failReason) {
        boolean success = "2000".equals(resultCode);
        String action = success ? ACTION_NICE_RESULT_SUCCESS : ACTION_NICE_RESULT_FAILED;

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("requestNo", requestNo);
        metadata.put("webTransactionId", webTransactionId);
        metadata.put("resultCode", resultCode);
        if (qimUserId != null) metadata.put("qimUserId", qimUserId);
        if (isNewUser != null) metadata.put("isNewUser", isNewUser);
        if (failReason != null) metadata.put("failReason", failReason);

        auditLogPublisher.publish(
                AuditLogPublisher.AuditEntry.builder()
                        .eventCategory(AuditLogEvent.CATEGORY_AUTH)
                        .eventAction(action)
                        .actorType(AuditLogEvent.ACTOR_USER)
                        .actorId(qimUserId)
                        .resourceType("NICE_AUTH_RESULT")
                        .resourceId(requestNo)
                        .correlationId(CorrelationIdHolder.get())
                        .outcome(success ? AuditLogEvent.OUTCOME_SUCCESS : AuditLogEvent.OUTCOME_FAILURE)
                        .outcomeDetail(failReason)
                        .metadata(metadata)
                        .build()
        );
    }

    /**
     * NICE CI 확인 감사 이벤트 발행
     *
     * <p>PII 보호: CI 원본 포함 금지. mbrDvsnCd(개인/기업 구분)만 기록.
     *
     * @param mbrDvsnCd  회원구분코드 (A101/A102)
     * @param resultCode 결과 코드
     * @param failReason 실패 사유
     */
    public void publishCiCheckEvent(String mbrDvsnCd, String resultCode, String failReason) {
        boolean success = "2000".equals(resultCode);
        String action = success ? ACTION_NICE_CI_CHECK_SUCCESS : ACTION_NICE_CI_CHECK_FAILED;

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("mbrDvsnCd", mbrDvsnCd);
        metadata.put("resultCode", resultCode);
        if (failReason != null) metadata.put("failReason", failReason);

        auditLogPublisher.publish(
                AuditLogPublisher.AuditEntry.builder()
                        .eventCategory(AuditLogEvent.CATEGORY_AUTH)
                        .eventAction(action)
                        .actorType(AuditLogEvent.ACTOR_USER)
                        .resourceType("CI_CHECK")
                        .correlationId(CorrelationIdHolder.get())
                        .outcome(success ? AuditLogEvent.OUTCOME_SUCCESS : AuditLogEvent.OUTCOME_FAILURE)
                        .outcomeDetail(failReason)
                        .metadata(metadata)
                        .build()
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // OACX 감사 이벤트
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * OACX 접근키 발급 감사 이벤트 발행
     *
     * @param fn         OACX 기능 코드
     * @param resultCode 결과 코드
     */
    public void publishOacxAccessInfoEvent(String fn, String resultCode) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("fn", fn);
        metadata.put("resultCode", resultCode);

        auditLogPublisher.publish(
                AuditLogPublisher.AuditEntry.builder()
                        .eventCategory(AuditLogEvent.CATEGORY_AUTH)
                        .eventAction(ACTION_OACX_ACCESS_INFO)
                        .actorType(AuditLogEvent.ACTOR_USER)
                        .resourceType("OACX_ACCESS_INFO")
                        .correlationId(CorrelationIdHolder.get())
                        .outcome("2000".equals(resultCode)
                                ? AuditLogEvent.OUTCOME_SUCCESS : AuditLogEvent.OUTCOME_FAILURE)
                        .metadata(metadata)
                        .build()
        );
    }

    /**
     * OACX 간편서명 결과 처리 감사 이벤트 발행
     *
     * <p>PII 보호: CI 원본 포함 금지. provider(naver/toss/pass 등)만 기록.
     *
     * @param provider   OACX provider 식별자 (PII 없음)
     * @param resultCode 결과 코드
     * @param failReason 실패 사유
     */
    public void publishOacxEasysignEvent(String provider, String resultCode, String failReason) {
        boolean success = "2000".equals(resultCode);
        String action = success ? ACTION_OACX_EASYSIGN_SUCCESS : ACTION_OACX_EASYSIGN_FAILED;

        Map<String, Object> metadata = new HashMap<>();
        if (provider != null) metadata.put("provider", provider);
        metadata.put("resultCode", resultCode);
        if (failReason != null) metadata.put("failReason", failReason);

        auditLogPublisher.publish(
                AuditLogPublisher.AuditEntry.builder()
                        .eventCategory(AuditLogEvent.CATEGORY_AUTH)
                        .eventAction(action)
                        .actorType(AuditLogEvent.ACTOR_USER)
                        .resourceType("OACX_EASYSIGN")
                        .correlationId(CorrelationIdHolder.get())
                        .outcome(success ? AuditLogEvent.OUTCOME_SUCCESS : AuditLogEvent.OUTCOME_FAILURE)
                        .outcomeDetail(failReason)
                        .metadata(metadata)
                        .build()
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 기업인증 콜백 감사 이벤트
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 기업인증 콜백 처리 감사 이벤트 발행
     *
     * @param txId       트랜잭션 ID (식별자용, PII 없음)
     * @param resultCode 결과 코드
     * @param failReason 실패 사유
     */
    public void publishCallbackEvent(String txId, String resultCode, String failReason) {
        boolean success = "2000".equals(resultCode);
        String action = success ? ACTION_CALLBACK_SUCCESS : ACTION_CALLBACK_FAILED;

        Map<String, Object> metadata = new HashMap<>();
        if (txId != null) metadata.put("txId", txId);
        metadata.put("resultCode", resultCode);
        if (failReason != null) metadata.put("failReason", failReason);

        auditLogPublisher.publish(
                AuditLogPublisher.AuditEntry.builder()
                        .eventCategory(AuditLogEvent.CATEGORY_AUTH)
                        .eventAction(action)
                        .actorType(AuditLogEvent.ACTOR_USER)
                        .resourceType("INTEGRATION_AUTH_CALLBACK")
                        .resourceId(txId)
                        .correlationId(CorrelationIdHolder.get())
                        .outcome(success ? AuditLogEvent.OUTCOME_SUCCESS : AuditLogEvent.OUTCOME_FAILURE)
                        .outcomeDetail(failReason)
                        .metadata(metadata)
                        .build()
        );
    }
}
