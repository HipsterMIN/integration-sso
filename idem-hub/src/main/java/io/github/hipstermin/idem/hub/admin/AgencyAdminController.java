package io.github.hipstermin.idem.hub.admin;

import io.github.hipstermin.idem.common.util.CorrelationIdHolder;
import io.github.hipstermin.idem.hub.admin.dto.AgencyCreateRequest;
import io.github.hipstermin.idem.hub.admin.dto.AgencyResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 기관 Admin REST API 컨트롤러
 *
 * <p><b>9개 엔드포인트 (설계서 §18.2)</b>:
 * <ol>
 *   <li>POST   /api/v1/admin/agencies            — 기관 등록</li>
 *   <li>GET    /api/v1/admin/agencies            — 기관 목록 조회</li>
 *   <li>GET    /api/v1/admin/agencies/{code}     — 기관 단건 조회</li>
 *   <li>PUT    /api/v1/admin/agencies/{code}     — 기관 정보 수정</li>
 *   <li>POST   /api/v1/admin/agencies/{code}/activate   — 기관 활성화</li>
 *   <li>POST   /api/v1/admin/agencies/{code}/deactivate — 기관 비활성화</li>
 *   <li>POST   /api/v1/admin/agencies/{code}/rotate-key — API Key 로테이션</li>
 *   <li>GET    /api/v1/admin/agencies/{code}/history    — 변경 이력 조회</li>
 *   <li>GET    /api/v1/admin/agencies/{code}/stats      — 연동 통계 조회</li>
 * </ol>
 *
 * <p><b>보안</b>: X-Admin-Token 헤더 검증 (AdminAuthInterceptor), 또는 내부망 전용 접근.
 * 운영 시 Admin API Gateway 또는 IP 화이트리스트 필수.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/agencies")
@RequiredArgsConstructor
public class AgencyAdminController {

    private final AgencyAdminService agencyAdminService;
    private final io.github.hipstermin.idem.hub.admin.auth.AdminTenantScope tenantScope;

    // ────────────────────────────────────────────────────────────────────────
    // 1. 기관 등록
    // POST /api/v1/admin/agencies
    // ────────────────────────────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<AgencyResponse> createAgency(
            io.github.hipstermin.idem.hub.admin.auth.AdminPrincipal admin,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @Valid @RequestBody AgencyCreateRequest request) {

        String cid = cid(correlationId);
        log.info("[AdminCtrl] 기관 등록 요청: agencyCode={} adminId={} cid={}", request.getAgencyCode(), admin.username(), cid);

        tenantScope.checkTenant(admin, null);   // 레거시 등록 API 는 DEFAULT Tenant 에 만든다
        AgencyResponse response = agencyAdminService.createAgency(request, admin.username());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // ────────────────────────────────────────────────────────────────────────
    // 2. 기관 목록 조회
    // GET /api/v1/admin/agencies?page=0&size=20
    // ────────────────────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<List<AgencyResponse>> listAgencies(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size,
            io.github.hipstermin.idem.hub.admin.auth.AdminPrincipal admin) {

        List<AgencyResponse> list = agencyAdminService.listAgencies(page, size);
        if (!admin.isGlobal()) {
            // S7 테넌트 범위: 자기 Tenant 의 Service 만
            list = list.stream().filter(a -> tenantScope.inScope(admin, a.getAgencyCode())).toList();
        }
        return ResponseEntity.ok(list);
    }

    // ────────────────────────────────────────────────────────────────────────
    // 3. 기관 단건 조회
    // GET /api/v1/admin/agencies/{agencyCode}
    // ────────────────────────────────────────────────────────────────────────

    @GetMapping("/{agencyCode}")
    public ResponseEntity<AgencyResponse> getAgency(@PathVariable String agencyCode,
                                                    io.github.hipstermin.idem.hub.admin.auth.AdminPrincipal admin) {
        tenantScope.checkService(admin, agencyCode);
        return ResponseEntity.ok(agencyAdminService.getAgency(agencyCode));
    }

    // ────────────────────────────────────────────────────────────────────────
    // 4. 기관 정보 수정
    // PUT /api/v1/admin/agencies/{agencyCode}
    // ────────────────────────────────────────────────────────────────────────

    @PutMapping("/{agencyCode}")
    public ResponseEntity<AgencyResponse> updateAgency(
            @PathVariable String agencyCode,
            io.github.hipstermin.idem.hub.admin.auth.AdminPrincipal admin,
            @RequestBody AgencyCreateRequest request) {

        log.info("[AdminCtrl] 기관 수정: agencyCode={} adminId={}", agencyCode, admin.username());
        tenantScope.checkService(admin, agencyCode);
        return ResponseEntity.ok(agencyAdminService.updateAgency(agencyCode, request, admin.username()));
    }

    // ────────────────────────────────────────────────────────────────────────
    // 5. 기관 활성화
    // POST /api/v1/admin/agencies/{agencyCode}/activate
    // ────────────────────────────────────────────────────────────────────────

    @PostMapping("/{agencyCode}/activate")
    public ResponseEntity<Map<String, Object>> activate(
            @PathVariable String agencyCode,
            io.github.hipstermin.idem.hub.admin.auth.AdminPrincipal admin) {

        tenantScope.checkService(admin, agencyCode);
        agencyAdminService.activate(agencyCode, admin.username());
        return ResponseEntity.ok(Map.of("agencyCode", agencyCode, "active", true, "adminId", admin.username()));
    }

    // ────────────────────────────────────────────────────────────────────────
    // 6. 기관 비활성화
    // POST /api/v1/admin/agencies/{agencyCode}/deactivate
    // ────────────────────────────────────────────────────────────────────────

    @PostMapping("/{agencyCode}/deactivate")
    public ResponseEntity<Map<String, Object>> deactivate(
            @PathVariable String agencyCode,
            io.github.hipstermin.idem.hub.admin.auth.AdminPrincipal admin) {

        tenantScope.checkService(admin, agencyCode);
        agencyAdminService.deactivate(agencyCode, admin.username());
        return ResponseEntity.ok(Map.of("agencyCode", agencyCode, "active", false, "adminId", admin.username()));
    }

    // ────────────────────────────────────────────────────────────────────────
    // 7. API Key 로테이션 (1회성 — 반환된 키는 다시 조회 불가)
    // POST /api/v1/admin/agencies/{agencyCode}/rotate-key
    // ────────────────────────────────────────────────────────────────────────

    @PostMapping("/{agencyCode}/rotate-key")
    public ResponseEntity<Map<String, String>> rotateApiKey(
            @PathVariable String agencyCode,
            io.github.hipstermin.idem.hub.admin.auth.AdminPrincipal admin) {

        log.warn("[AdminCtrl] API Key 로테이션: agencyCode={} adminId={}", agencyCode, admin.username());
        tenantScope.checkService(admin, agencyCode);
        Map<String, String> result = agencyAdminService.rotateApiKey(agencyCode, admin.username());
        return ResponseEntity.ok(result);
    }

    // ────────────────────────────────────────────────────────────────────────
    // 8. 변경 이력 조회
    // GET /api/v1/admin/agencies/{agencyCode}/history
    // ────────────────────────────────────────────────────────────────────────

    @GetMapping("/{agencyCode}/history")
    public ResponseEntity<List<Map<String, Object>>> getHistory(@PathVariable String agencyCode, io.github.hipstermin.idem.hub.admin.auth.AdminPrincipal admin) {
        tenantScope.checkService(admin, agencyCode);
        return ResponseEntity.ok(agencyAdminService.getHistory(agencyCode));
    }

    // ────────────────────────────────────────────────────────────────────────
    // 9. 연동 통계 조회
    // GET /api/v1/admin/agencies/{agencyCode}/stats
    // ────────────────────────────────────────────────────────────────────────

    @GetMapping("/{agencyCode}/stats")
    public ResponseEntity<Map<String, Object>> getStats(@PathVariable String agencyCode, io.github.hipstermin.idem.hub.admin.auth.AdminPrincipal admin) {
        tenantScope.checkService(admin, agencyCode);
        return ResponseEntity.ok(agencyAdminService.getStats(agencyCode));
    }

    // ────────────────────────────────────────────────────────────────────────
    // 전역 예외 처리 (Admin 전용 간단 핸들러)
    // ────────────────────────────────────────────────────────────────────────

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "BAD_REQUEST", "message", e.getMessage()));
    }

    private String cid(String correlationId) {
        if (correlationId != null && !correlationId.isBlank()) {
            CorrelationIdHolder.set(correlationId);
            return correlationId;
        }
        return CorrelationIdHolder.generate();
    }
}
