package io.github.hipstermin.idem.hub.slo;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.crypto.CryptoProviders;
import io.github.hipstermin.idem.hub.audit.AuditLogPublisher;
import io.github.hipstermin.idem.hub.fe.session.FeSession;
import io.github.hipstermin.idem.hub.qim.sp.domain.InstMbrIdMapping;
import io.github.hipstermin.idem.hub.qim.sp.infrastructure.InstMbrIdMappingRepository;
import io.github.hipstermin.idem.hub.webhook.WebhookDispatcherService;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * SLO (Single Logout) 오케스트레이션 서비스 구현체
 * 설계서 §13.3 / Sprint 2 P1-01~03
 *
 * <p><b>실행 순서</b>:
 * <ol>
 *   <li>Q-Sign → Keycloak 세션 종료 (비치명적: 실패해도 계속)</li>
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

    private final RestTemplate                 restTemplate;
    private final WebhookDispatcherService     webhookDispatcherService;
    private final AuditLogPublisher            auditLogPublisher;
    private final InstMbrIdMappingRepository   instMbrIdMappingRepository;
    private final ObjectMapper                 objectMapper;

    /** Q-Sign 서비스 내부 베이스 URL */
    @Value("${idem.hub.gate.base-url:http://localhost:8081}")
    private String qsignBaseUrl;

    /** Q-Sign 내부 서명 비밀키 (HMAC-SHA256 서명 생성용) */
    @Value("${idem.hub.gate.internal-sig-secret:}")
    private String internalSigSecret;

    @Value("${idem.hub.gate.internal-sig-ttl-seconds:60}")
    private int internalSigTtlSeconds;

    // ════════════════════════════════════════════════════════════════════════

    @Override
    public void executeSlo(FeSession session, String correlationId) {
        String qimUserId    = session.getQimUserId();
        String feSessionId  = session.getFeSessionId();

        log.info("[SLO] SLO 시작: qimUserId={} feSessionId={} correlationId={}",
                qimUserId, feSessionId, correlationId);

        // ① Q-Sign → Keycloak 세션 종료 (비치명적) — S6 PR-2: FE 세션이 기억하는 Keycloak sub·sid 로 정확히 그 세션만
        if (session.getIdpSub() != null || session.getIdpSid() != null) {
            revokeKeycloakSessionSafely(session.getIdpSub(), session.getIdpSid(), qimUserId, correlationId);
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
     * Q-Sign 내부 API를 통해 Keycloak 세션 강제 종료
     *
     * <p>POST {qsignBaseUrl}/api/v1/internal/session/logout
     * X-Internal-Sig HMAC-SHA256 서명 포함
     */
    private void revokeKeycloakSessionSafely(String idpSub, String idpSid, String qimUserId, String correlationId) {
        try {
            String url = qsignBaseUrl + "/api/v1/internal/session/logout";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Correlation-Id",  correlationId);
            headers.set("X-Internal-Caller", "idem-hub");
            headers.set("X-Internal-Sig",    buildInternalSig(correlationId));

            // S6 PR-2: 종전에는 qimUserId 를 Keycloak username 으로 넘겨 항상 실패했다 — 이제 id_token 의 sub·sid 를 넘긴다
            Map<String, String> body = new java.util.HashMap<>();
            body.put("correlationId", correlationId);
            body.put("qimUserId", qimUserId);
            if (idpSub != null) body.put("sub", idpSub);
            if (idpSid != null) body.put("sid", idpSid);

            ResponseEntity<Void> resp = restTemplate.exchange(
                    url, HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Void.class);

            if (resp.getStatusCode().is2xxSuccessful()) {
                log.info("[SLO] Q-Sign Keycloak 세션 종료 요청 완료: qimUserId={} correlationId={}",
                        qimUserId, correlationId);
            } else {
                log.warn("[SLO] Q-Sign 세션 종료 응답 이상: status={} qimUserId={} correlationId={}",
                        resp.getStatusCode(), qimUserId, correlationId);
            }
        } catch (Exception e) {
            log.warn("[SLO] Keycloak 세션 종료 실패 (비치명적): qimUserId={} correlationId={} cause={}",
                    qimUserId, correlationId, e.getMessage());
        }
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

    // ── private: X-Internal-Sig 생성 ─────────────────────────────────────

    /**
     * HMAC-SHA256 내부 서명 생성
     *
     * <p>payload = "{correlationId}:{epochSeconds}"
     * sig = HMAC-SHA256(payload, internalSigSecret) → Hex 문자열
     *
     * <p>internalSigSecret이 비어 있으면 경고 후 임시 식별자 반환.
     * (비밀키 미설정 시 Q-Sign 측 검증이 거부하므로 SLO 2단계는 비치명적 실패)
     */
    private String buildInternalSig(String correlationId) {
        if (internalSigSecret == null || internalSigSecret.isBlank()) {
            // D2 fail-secure: 더미 서명("sig-unsigned")으로 SLO 실패를 숨기지 않는다
            throw new IllegalStateException("IDEM_HUB_INTERNAL_SIG_SECRET 미설정 — SLO 내부 서명 불가: correlationId=" + correlationId);
        }
        try {
            long epochSeconds = System.currentTimeMillis() / 1000L;
            String payload = correlationId + ":" + epochSeconds;
            return CryptoProviders.current().hmacSha256Hex(internalSigSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8), payload);
        } catch (Exception e) {
            throw new IllegalStateException("SLO X-Internal-Sig 생성 실패: correlationId=" + correlationId + " — " + e.getMessage(), e);
        }
    }
}
