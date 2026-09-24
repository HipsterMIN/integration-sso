package io.github.hipstermin.idem.hub.kr.auth.audit;

import io.github.hipstermin.idem.common.event.AuditLogEvent;
import io.github.hipstermin.idem.common.util.CorrelationIdHolder;
import io.github.hipstermin.idem.hub.audit.AuditLogPublisher;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * KR 에디션 감사 이벤트 — NICE CI 조회·기업인증 콜백. (S8-a: 코어 {@code AuthAuditService} 에서 분리)
 */
@Service
@RequiredArgsConstructor
public class KrAuthAuditService {

    public static final String ACTION_NICE_CI_CHECK_SUCCESS = "AUTH_NICE_CI_CHECK_SUCCESS";
    public static final String ACTION_NICE_CI_CHECK_FAILED  = "AUTH_NICE_CI_CHECK_FAILED";
    public static final String ACTION_CALLBACK_SUCCESS      = "AUTH_CALLBACK_SUCCESS";
    public static final String ACTION_CALLBACK_FAILED       = "AUTH_CALLBACK_FAILED";

    private final AuditLogPublisher auditLogPublisher;

    /** NICE CI 조회 감사 (mbrDvsnCd = SMES 회원 구분 코드) */
    public void publishCiCheckEvent(String mbrDvsnCd, String resultCode, String failReason) {
        boolean success = "2000".equals(resultCode);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("mbrDvsnCd", mbrDvsnCd);
        metadata.put("resultCode", resultCode);
        if (failReason != null) metadata.put("failReason", failReason);
        auditLogPublisher.publish(AuditLogPublisher.AuditEntry.builder()
                .eventCategory(AuditLogEvent.CATEGORY_AUTH)
                .eventAction(success ? ACTION_NICE_CI_CHECK_SUCCESS : ACTION_NICE_CI_CHECK_FAILED)
                .actorType(AuditLogEvent.ACTOR_USER)
                .resourceType("CI_CHECK")
                .correlationId(CorrelationIdHolder.get())
                .outcome(success ? AuditLogEvent.OUTCOME_SUCCESS : AuditLogEvent.OUTCOME_FAILURE)
                .outcomeDetail(failReason)
                .metadata(metadata)
                .build());
    }

    /** 기업인증(통합인증 서버) 콜백 감사 */
    public void publishCallbackEvent(String txId, String resultCode, String failReason) {
        boolean success = "2000".equals(resultCode);
        Map<String, Object> metadata = new HashMap<>();
        if (txId != null) metadata.put("txId", txId);
        metadata.put("resultCode", resultCode);
        if (failReason != null) metadata.put("failReason", failReason);
        auditLogPublisher.publish(AuditLogPublisher.AuditEntry.builder()
                .eventCategory(AuditLogEvent.CATEGORY_AUTH)
                .eventAction(success ? ACTION_CALLBACK_SUCCESS : ACTION_CALLBACK_FAILED)
                .actorType(AuditLogEvent.ACTOR_USER)
                .resourceType("INTEGRATION_AUTH_CALLBACK")
                .resourceId(txId)
                .correlationId(CorrelationIdHolder.get())
                .outcome(success ? AuditLogEvent.OUTCOME_SUCCESS : AuditLogEvent.OUTCOME_FAILURE)
                .outcomeDetail(failReason)
                .metadata(metadata)
                .build());
    }
}
