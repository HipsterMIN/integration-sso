package io.github.hipstermin.idem.hub.admin;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hipstermin.idem.common.error.ErrorResponse;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.hub.tenant.TenantProfile;
import io.github.hipstermin.idem.hub.tenant.TenantProfileService;
import io.github.hipstermin.idem.hub.tenant.TenantProfileValidator;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant Profile Admin API (S2) — 기관 설정을 선언적 문서 하나로 읽고 쓴다.
 *
 * <pre>
 * GET /api/v1/admin/tenants/profile-schema          JSON Schema (콘솔 폼 생성·클라이언트 검증용)
 * GET /api/v1/admin/tenants/{code}/profile          현재 프로파일 (저장분 + 컬럼 합성)
 * PUT /api/v1/admin/tenants/{code}/profile          전체 치환. 기관이 없으면 생성 (프로파일만으로 온보딩)
 * </pre>
 *
 * <p>기존 {@code /api/v1/admin/agencies} 는 유지된다(컬럼 단위 수정). 두 경로 모두 저장 시 프로파일과 컬럼을 일치시킨다.
 * 관리자 인증은 아직 없다 — {@code execution-plan.md} P1 / 범용화 S7 에서 {@code X-Admin-Id} 를 인증된 신원으로 대체한다.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/tenants")
@RequiredArgsConstructor
public class TenantProfileAdminController {

    private final TenantProfileService   tenantProfileService;
    private final TenantProfileValidator validator;

    @GetMapping(value = "/profile-schema", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> schema() {
        return ResponseEntity.ok(validator.schemaText());
    }

    @GetMapping("/{tenantCode}/profile")
    public ResponseEntity<TenantProfile> get(@PathVariable String tenantCode) {
        return ResponseEntity.ok(tenantProfileService.get(tenantCode));
    }

    @PutMapping(value = "/{tenantCode}/profile", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TenantProfile> put(
            @PathVariable String tenantCode,
            @RequestHeader(value = "X-Admin-Id", defaultValue = "SYSTEM") String adminId,
            @RequestHeader(value = "X-Change-Reason", required = false) String changeReason,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestBody JsonNode body) {
        String cid = correlationId != null ? correlationId : UUID.randomUUID().toString();
        log.info("[TenantProfileCtrl] PUT profile: tenantCode={} adminId={} cid={}", tenantCode, adminId, cid);
        return ResponseEntity.ok(tenantProfileService.put(tenantCode, body, adminId, changeReason, cid));
    }

    /**
     * 이 컨트롤러의 PlatformException 은 <b>상세 메시지</b>(스키마 위반 항목·코드 불일치 사유)를 그대로 돌려준다.
     * 전역 핸들러는 기본 메시지만 내보내므로, 관리자가 어느 필드가 틀렸는지 알 수 없다.
     * 관리 API 는 내부 호출자용이라 상세를 노출해도 무방하다.
     */
    @ExceptionHandler(PlatformException.class)
    public ResponseEntity<ErrorResponse> handlePlatformException(PlatformException ex) {
        log.warn("[TenantProfileCtrl] {} cid={} : {}", ex.getErrorCode().getCode(), ex.getCorrelationId(), ex.getMessage());
        return ResponseEntity.status(ex.getErrorCode().getHttpStatus())
                .body(ErrorResponse.builder()
                        .code(ex.getErrorCode().getCode())
                        .message(ex.getMessage())
                        .correlationId(ex.getCorrelationId())
                        .timestamp(Instant.now())
                        .build());
    }
}
