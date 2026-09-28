package io.github.hipstermin.idem.hub.slo;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.hub.audit.AuditLogPublisher;
import io.github.hipstermin.idem.hub.fe.session.FeSession;
import io.github.hipstermin.idem.hub.qim.sp.domain.InstMbrIdMapping;
import io.github.hipstermin.idem.hub.qim.sp.infrastructure.InstMbrIdMappingRepository;
import io.github.hipstermin.idem.hub.webhook.WebhookDispatcherService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;

/**
 * SLO (Single Logout) 오케스트레이션 서비스 구현체
 * 설계서 §13.3 / Sprint 2 P1-01~03
 *
 * <p><b>실행 순서</b>:
 * <ol>
 *   <li>Q-Sign → Keycloak 세션 종료 (실패하면 재시도 큐 — 1.1)</li>
 *   <li>기관 로그아웃 Webhook Outbox 적재 (비치명적)</li>
 *   <li>감사 로그 기록 (비치명적)</li>
 * </ol>
 *
 * <p><b>비치명적 원칙</b>:
 * 각 단계 실패가 전체 SLO 흐름을 중단하지 않는다.
 * feSession 만료는 SloController에서 이미 완료된 상태로 이 메서드 진입.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SloServiceImpl implements SloService {

    private static final String EVENT_CATEGORY_SESSION = "SESSION";
    private static final String EVENT_ACTION_LOGOUT    = "SLO_LOGOUT";
    private static final String ACTOR_TYPE_USER        = "USER";
    private static final String RESOURCE_TYPE_SESSION  = "FE_SESSION";

    private final IdpSessionRevoker            idpSessionRevoker;       // 1.1: gate 호출 하나로 (첫 시도·재시도 공용)
    private final SloIdpLogoutRetryQueue       retryQueue;              // 1.1: 실패 시 적재
    private final WebhookDispatcherService     webhookDispatcherService;
    private final AuditLogPublisher            auditLogPublisher;
    private final InstMbrIdMappingRepository   instMbrIdMappingRepository;
    private final ObjectMapper                 objectMapper;

    // ════════════════════════════════════════════════════════════════════════

    @Override
    public void executeSlo(FeSession session, String correlationId) {
        String qimUserId    = session.getQimUserId();
        String feSessionId  = session.getFeSessionId();

        log.info("[SLO] SLO 시작: qimUserId={} feSessionId={} correlationId={}",
                qimUserId, feSessionId, correlationId);

        // ① Q-Sign → Keycloak 세션 종료 (비치명적) — S6 PR-2: FE 세션이 기억하는 Keycloak sub·sid 로 정확히 그 세션만
        if (session.getIdpSub() != null || session.getIdpSid() != null) {
            revokeKeycloakSessionOrEnqueue(session, correlationId);
        } else {
            log.info("[SLO] Keycloak 세션 정보 없음(비 Keycloak 로그인) — IdP 단계 건너뜀: qimUserId={} correlationId={}", qimUserId, correlationId);
        }

        // ② 기관 로그아웃 Webhook Outbox 적재 (비치명적)
        enqueueLogoutWebhookSafely(qimUserId, correlationId);

        // ③ 감사 로그 기록 (비치명적)
        publishAuditLogSafely(qimUserId, feSessionId, correlationId);

        log.info("[SLO] SLO 완료: qimUserId={} correlationId={}", qimUserId, correlationId);
    }

    // ── private: ① Keycloak 세션 종료 ──────────────────────────────────────

    /**
     * gate 내부 API 로 Keycloak 세션 종료. 1.1: 실패(비 2xx·예외·outcome=FAILED)는 WARN 으로 끝내지 않고
     * {@code slo_idp_logout_retry} 에 적재해 {@link SloIdpLogoutRetryRelay} 가 지수 백오프로 재시도한다.
     */
    private void revokeKeycloakSessionOrEnqueue(FeSession session, String correlationId) {
        String qimUserId = session.getQimUserId();
        IdpSessionRevoker.Result r = idpSessionRevoker.revoke(session.getIdpSub(), session.getIdpSid(), qimUserId, correlationId);
        if (r.success()) {
            log.info("[SLO] Q-Sign Keycloak 세션 종료 완료: qimUserId={} outcome={} correlationId={}", qimUserId, r.outcome(), correlationId);
            return;
        }
        retryQueue.enqueue(session.getFeSessionId(), qimUserId, session.getIdpSub(), session.getIdpSid(), correlationId, r.error());
    }

    // ── private: ② 기관 로그아웃 Webhook ────────────────────────────────────

    /**
     * instMbrId 조회 후 기관 로그아웃 Webhook Outbox 적재
     *
     * <p>WebhookDispatcherService.enqueueForUserLogout() 위임.
     * instMbrId 조회 실패 시 qimUserId 기반으로 폴백하지 않고 경고 로그만 출력.
     */
    private void enqueueLogoutWebhookSafely(String qimUserId, String correlationId) {
        try {
            Optional<InstMbrIdMapping> mappingOpt =
                    instMbrIdMappingRepository.findByQimUserId(qimUserId);

            if (mappingOpt.isEmpty()) {
                log.warn("[SLO] instMbrId 매핑 없음 — Webhook 스킵: qimUserId={} correlationId={}",
                        qimUserId, correlationId);
                return;
            }

            String instMbrId = mappingOpt.get().getInstMbrId();
            webhookDispatcherService.enqueueForUserLogout(instMbrId, qimUserId, correlationId);
            log.info("[SLO] 기관 로그아웃 Webhook Outbox 적재 완료: qimUserId={} instMbrId={} correlationId={}",
                    qimUserId, instMbrId, correlationId);
        } catch (Exception e) {
            log.error("[SLO] 기관 로그아웃 Webhook 적재 실패 (비치명적): qimUserId={} correlationId={} cause={}",
                    qimUserId, correlationId, e.getMessage());
        }
    }

    // ── private: ③ 감사 로그 ────────────────────────────────────────────────

    private void publishAuditLogSafely(String qimUserId, String feSessionId, String correlationId) {
        try {
            auditLogPublisher.publish(
                    AuditLogPublisher.AuditEntry.builder()
                            .eventCategory(EVENT_CATEGORY_SESSION)
                            .eventAction(EVENT_ACTION_LOGOUT)
                            .actorType(ACTOR_TYPE_USER)
                            .actorId(qimUserId)
                            .resourceType(RESOURCE_TYPE_SESSION)
                            .resourceId(feSessionId)
                            .correlationId(correlationId)
                            .build());
        } catch (Exception e) {
            log.warn("[SLO] 감사 로그 기록 실패 (비치명적): qimUserId={} cause={}", qimUserId, e.getMessage());
        }
    }

}
