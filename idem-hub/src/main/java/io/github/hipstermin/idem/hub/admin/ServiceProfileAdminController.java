package io.github.hipstermin.idem.hub.admin;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.common.domain.UserStatus;
import io.github.hipstermin.idem.common.error.ErrorResponse;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.hub.infrastructure.ServiceAccess;
import io.github.hipstermin.idem.hub.policy.PolicyEngine;
import io.github.hipstermin.idem.hub.policy.rule.PolicyContext;
import io.github.hipstermin.idem.hub.policy.rule.PolicyDecision;
import io.github.hipstermin.idem.hub.policy.rule.PolicyEvaluation;
import io.github.hipstermin.idem.hub.protocol.oidcrp.OidcClientSecret;
import io.github.hipstermin.idem.hub.protocol.oidcrp.OidcClientStatus;
import io.github.hipstermin.idem.hub.protocol.oidcrp.OidcRpClientProvisioner;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfile;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfileService;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfileValidator;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service Profile Admin API (S2) — 기관 설정을 선언적 문서 하나로 읽고 쓴다.
 *
 * <pre>
 * GET /api/v1/admin/services/profile-schema          JSON Schema (콘솔 폼 생성·클라이언트 검증용)
 * GET /api/v1/admin/services/{code}/profile          현재 프로파일 (저장분 + 컬럼 합성)
 * PUT /api/v1/admin/services/{code}/profile          전체 치환. 기관이 없으면 생성 (프로파일만으로 온보딩)
 * </pre>
 *
 * <p>기존 {@code /api/v1/admin/agencies} 는 유지된다(컬럼 단위 수정). 두 경로 모두 저장 시 프로파일과 컬럼을 일치시킨다.
 * 관리자 인증은 S7 — 세션 쿠키({@code AdminAuthFilter})와 역할·테넌트 범위. 종전 {@code X-Admin-Id} 헤더는 없다.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/services")
@RequiredArgsConstructor
public class ServiceProfileAdminController {

    private final ServiceProfileService   serviceProfileService;
    private final ServiceProfileValidator validator;
    private final PolicyEngine           policyEngine;
    private final OidcRpClientProvisioner oidcRpProvisioner;
    private final io.github.hipstermin.idem.hub.admin.auth.AdminTenantScope tenantScope;

    @GetMapping(value = "/profile-schema", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> schema(io.github.hipstermin.idem.hub.admin.auth.AdminPrincipal admin) {
        return ResponseEntity.ok(validator.schemaText());
    }

    @GetMapping("/{serviceCode}/profile")
    public ResponseEntity<ServiceProfile> get(@PathVariable String serviceCode, io.github.hipstermin.idem.hub.admin.auth.AdminPrincipal admin) {
        tenantScope.checkService(admin, serviceCode);
        return ResponseEntity.ok(serviceProfileService.get(serviceCode));
    }

    @PutMapping(value = "/{serviceCode}/profile", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ServiceProfile> put(
            @PathVariable String serviceCode,
            io.github.hipstermin.idem.hub.admin.auth.AdminPrincipal admin,
            @RequestHeader(value = "X-Change-Reason", required = false) String changeReason,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestBody JsonNode body) {
        String cid = correlationId != null ? correlationId : UUID.randomUUID().toString();
        // S7 테넌트 범위: 기존 Service 는 그 Tenant 여야 하고, 본문의 service.tenant 도 범위 안이어야 한다
        tenantScope.checkService(admin, serviceCode);
        tenantScope.checkTenant(admin, body != null ? body.path("service").path("tenant").asText(null) : null);
        log.info("[ServiceProfileCtrl] PUT profile: serviceCode={} adminId={} cid={}", serviceCode, admin.username(), cid);
        return ResponseEntity.ok(serviceProfileService.put(serviceCode, body, admin.username(), changeReason, cid));
    }

    /** S6: 프로비저닝된 OIDC client 상태 — secret 은 보이지 않는다. */
    @GetMapping("/{serviceCode}/oidc-client")
    public ResponseEntity<OidcClientStatus> oidcClient(
            @PathVariable String serviceCode,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            io.github.hipstermin.idem.hub.admin.auth.AdminPrincipal admin) {
        tenantScope.checkService(admin, serviceCode);
        serviceProfileService.get(serviceCode); // 등록된 기관인지 먼저 (404 E-IDO)
        return ResponseEntity.ok(oidcRpProvisioner.status(serviceCode,
                correlationId != null ? correlationId : UUID.randomUUID().toString()));
    }

    /** S6: client secret 회전 — 새 secret 은 이 응답에서만 한 번 보인다. */
    @PostMapping("/{serviceCode}/oidc-client/secret")
    public ResponseEntity<OidcClientSecret> rotateOidcSecret(
            @PathVariable String serviceCode,
            io.github.hipstermin.idem.hub.admin.auth.AdminPrincipal admin,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        tenantScope.checkService(admin, serviceCode);
        serviceProfileService.get(serviceCode);
        String cid = correlationId != null ? correlationId : UUID.randomUUID().toString();
        log.info("[ServiceProfileCtrl] OIDC secret 회전: serviceCode={} adminId={} cid={}", serviceCode, admin.username(), cid);
        return ResponseEntity.ok(oidcRpProvisioner.rotateSecret(serviceCode, admin.username(), cid));
    }

    /** 정책 시뮬레이션 요청 — 값이 없는 항목은 해당 규칙이 SKIP 된다. {@code at} 은 점검 시간대 판정 시각(생략 시 지금). */
    /** {@code assigned}: S8-b 할당 여부 가정 (null 이면 ASSIGNMENT 규칙은 '할당 정보 미제공' 으로 SKIP) */
    public record PolicySimulationRequest(String authLevel, String providerCode, String userStatus, Instant at, Boolean assigned) {}
    public record PolicySimulationResponse(boolean allowed, List<PolicyDecision> decisions) {}

    /**
     * 정책 시뮬레이션 (S3) — 저장된 프로파일로 "이런 요청이 오면 어떤 규칙이 어떻게 판정하는가" 를 실제 발급 없이 본다.
     * 모든 규칙을 끝까지 평가한다(첫 거부에서 멈추지 않음).
     */
    @PostMapping(value = "/{serviceCode}/policy/simulate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PolicySimulationResponse> simulate(
            @PathVariable String serviceCode,
            @RequestBody PolicySimulationRequest req,
            io.github.hipstermin.idem.hub.admin.auth.AdminPrincipal admin) {
        tenantScope.checkService(admin, serviceCode);
        ServiceProfile profile = serviceProfileService.get(serviceCode);
        UserStatus status = parseStatus(req.userStatus());
        PolicyContext ctx = PolicyContext.builder()
                .serviceCode(serviceCode)
                .profile(profile)
                .authLevel(AuthResult.AuthLevel.parse(req.authLevel()).orElse(null))
                .providerCode(req.providerCode())
                .userStatus(status == null ? null : () -> status)
                .serviceAccess(req.assigned() == null ? null
                        : () -> new ServiceAccess(true, req.assigned(), req.assigned() ? "SIMULATION" : null, List.of()))
                .now(req.at())
                .build();
        PolicyEvaluation eval = policyEngine.evaluate(ctx, false);
        return ResponseEntity.ok(new PolicySimulationResponse(eval.allowed(), eval.decisions()));
    }

    private static UserStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return UserStatus.valueOf(raw.trim().toUpperCase()); } catch (IllegalArgumentException e) { return null; }
    }

    /**
     * 이 컨트롤러의 PlatformException 은 <b>상세 메시지</b>(스키마 위반 항목·코드 불일치 사유)를 그대로 돌려준다.
     * 전역 핸들러는 기본 메시지만 내보내므로, 관리자가 어느 필드가 틀렸는지 알 수 없다.
     * 관리 API 는 내부 호출자용이라 상세를 노출해도 무방하다.
     */
    @ExceptionHandler(PlatformException.class)
    public ResponseEntity<ErrorResponse> handlePlatformException(PlatformException ex) {
        log.warn("[ServiceProfileCtrl] {} cid={} : {}", ex.getErrorCode().getCode(), ex.getCorrelationId(), ex.getMessage());
        return ResponseEntity.status(ex.getErrorCode().getHttpStatus())
                .body(ErrorResponse.builder()
                        .code(ex.getErrorCode().getCode())
                        .message(ex.getMessage())
                        .correlationId(ex.getCorrelationId())
                        .timestamp(Instant.now())
                        .build());
    }
}
