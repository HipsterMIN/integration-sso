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
    public static final String ACTION_PROVIDER_INITIATED     = "AUTH_PROVIDER_INITIATED";
    public static final String ACTION_PROVIDER_INITIATE_FAILED = "AUTH_PROVIDER_INITIATE_FAILED";
    public static final String ACTION_PROVIDER_VERIFIED      = "AUTH_PROVIDER_VERIFIED";
    public static final String ACTION_PROVIDER_VERIFY_FAILED = "AUTH_PROVIDER_VERIFY_FAILED";
    public static final String ACTION_NICE_CI_CHECK_SUCCESS = "AUTH_NICE_CI_CHECK_SUCCESS";
    public static final String ACTION_NICE_CI_CHECK_FAILED  = "AUTH_NICE_CI_CHECK_FAILED";
    public static final String ACTION_CALLBACK_SUCCESS      = "AUTH_CALLBACK_SUCCESS";
    public static final String ACTION_CALLBACK_FAILED       = "AUTH_CALLBACK_FAILED";

    private final AuditLogPublisher auditLogPublisher;

    // ─────────────────────────────────────────────────────────────────────────
    // 본인인증 제공자(SPI) 감사 이벤트 — 벤더 무관 (S5a)
    // ─────────────────────────────────────────────────────────────────────────

    /** 인증 시작(initiate). resultCode 2000 = 성공, 그 외 실패. */
    public void publishProviderInitiate(String providerCode, String txId, String resultCode, String failReason) {
        boolean success = "2000".equals(resultCode);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("providerCode", providerCode);
        if (txId != null) metadata.put("txId", txId);
        metadata.put("resultCode", resultCode);
        if (failReason != null) metadata.put("failReason", failReason);
        auditLogPublisher.publish(
                AuditLogPublisher.AuditEntry.builder()
                        .eventCategory(AuditLogEvent.CATEGORY_AUTH)
                        .eventAction(success ? ACTION_PROVIDER_INITIATED : ACTION_PROVIDER_INITIATE_FAILED)
                        .actorType(AuditLogEvent.ACTOR_USER)
                        .resourceType("AUTH_PROVIDER")
                        .resourceId(providerCode)
                        .correlationId(CorrelationIdHolder.get())
                        .outcome(success ? AuditLogEvent.OUTCOME_SUCCESS : AuditLogEvent.OUTCOME_FAILURE)
                        .outcomeDetail(failReason)
                        .metadata(metadata)
                        .build()
        );
    }

    /** 인증 완료(complete) + registry 등록 결과. */
    public void publishProviderComplete(String providerCode, String txId, String resultCode,
                                        String qimUserId, Boolean isNewUser, String failReason) {
        boolean success = "2000".equals(resultCode);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("providerCode", providerCode);
        if (txId != null) metadata.put("txId", txId);
        metadata.put("resultCode", resultCode);
        if (qimUserId != null) metadata.put("qimUserId", qimUserId);
        if (isNewUser != null) metadata.put("isNewUser", isNewUser);
        if (failReason != null) metadata.put("failReason", failReason);
        auditLogPublisher.publish(
                AuditLogPublisher.AuditEntry.builder()
                        .eventCategory(AuditLogEvent.CATEGORY_AUTH)
                        .eventAction(success ? ACTION_PROVIDER_VERIFIED : ACTION_PROVIDER_VERIFY_FAILED)
                        .actorType(AuditLogEvent.ACTOR_USER)
                        .actorId(qimUserId)
                        .resourceType("AUTH_PROVIDER")
                        .resourceId(providerCode)
                        .correlationId(CorrelationIdHolder.get())
                        .outcome(success ? AuditLogEvent.OUTCOME_SUCCESS : AuditLogEvent.OUTCOME_FAILURE)
                        .outcomeDetail(failReason)
                        .metadata(metadata)
                        .build()
        );
    }

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
