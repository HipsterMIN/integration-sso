package io.github.hipstermin.idem.tenant.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/**
 * 기관 회원 조회 API — Q-IM AgencyMemberLookupService 전용
 *
 * <p>Q-IM 전환 세션의 {@code fetchCandidates()} 단계에서 호출된다.
 * identifierHash(= SHA-256(CI + agencyCode)) 기반으로 해당 기관 내 기존 회원을 조회한다.
 *
 * <p><b>보안</b>: 이 엔드포인트는 Q-IM 내부에서만 호출 가능하다.
 * {@code X-Internal-Api-Key} 헤더 검증으로 외부 접근을 차단한다.
 * (AgencyApiKeyInterceptor 는 {@code /agency/entry/**} 에만 적용 — 별도 Q-IM 키 검증 사용)
 *
 * <h3>요청 / 응답 형식</h3>
 * <pre>
 * POST /api/v1/members/lookup
 * Headers:
 *   X-Qim-Internal-Key: {qim.internal-api-key}
 *   X-Agency-Code: GOV_SMES
 *   X-Correlation-Id: {uuid}
 * Body: { "identifierHash": "sha256hex...", "agencyCode": "GOV_SMES" }
 *
 * Response 200:
 * { "found": true,
 *   "member": {
 *     "agencyCode": "GOV_SMES", "agencyName": "소상공인시장진흥공단",
 *     "memberId": "uuid...", "nameMasked": "홍*동",
 *     "lastLoginAt": "...", "joinedAt": "..."
 *   }
 * }
 * Response 200 (미존재):
 * { "found": false }
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/members")
@RequiredArgsConstructor
public class AgencyMemberLookupController {

    private final JdbcTemplate jdbcTemplate;

    /** Q-IM 내부 API 키 (환경변수 AGENCY_QIM_INTERNAL_KEY, 기본값 PoC) */
    @org.springframework.beans.factory.annotation.Value(
            "${agency-stub.qim-internal-key:qim-internal-key-dev-001}")
    private String qimInternalKey;

    @org.springframework.beans.factory.annotation.Value(
            "${agency-stub.code:AGENCY_STUB_001}")
    private String agencyCode;

    @org.springframework.beans.factory.annotation.Value(
            "${agency-stub.name:기관 스텁}")
    private String agencyName;

    // ── 회원 조회 ────────────────────────────────────────────────────────────

    /**
     * POST /api/v1/members/lookup
     * identifierHash 기반 기존 회원 존재 여부 조회 및 기본 정보 반환.
     */
    @PostMapping("/lookup")
    public ResponseEntity<?> lookup(
            @RequestHeader(value = "X-Qim-Internal-Key", required = false) String internalKey,
            @RequestHeader(value = "X-Correlation-Id",   required = false) String correlationId,
            @RequestBody LookupRequest request) {

        // ① Q-IM 내부 키 검증
        if (!secureEquals(qimInternalKey, internalKey)) {
            log.warn("[MemberLookup] Q-IM 내부 키 불일치 correlationId={}", correlationId);
            return ResponseEntity.status(401)
                    .body(Map.of("error", "UNAUTHORIZED", "correlationId", asStr(correlationId)));
        }

        String idHash = request.identifierHash();
        if (idHash == null || idHash.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "MISSING_IDENTIFIER_HASH"));
        }

        log.debug("[MemberLookup] 조회 시작: agencyCode={} correlationId={}", agencyCode, correlationId);

        // ② agency_user 테이블에서 agency_subject_id 매핑 조회
        //    agency_subject_id = SHA-256(qimUserId + agencyCode + salt) ≒ identifierHash
        //    PoC: identifierHash 를 agency_subject_id 컬럼으로 직접 조회
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT agency_user_id, agency_code, status, " +
                "last_login_at, created_at " +
                "FROM agency_stub.agency_user " +
                "WHERE agency_subject_id = ? AND status = 'ACTIVE'",
                idHash
        );

        if (rows.isEmpty()) {
            log.debug("[MemberLookup] 회원 미발견: agencyCode={} correlationId={}", agencyCode, correlationId);
            return ResponseEntity.ok(Map.of("found", false));
        }

        Map<String, Object> row = rows.get(0);
        String memberId    = asStr(row.get("agency_user_id"));
        Object lastLoginRaw = row.get("last_login_at");
        Object joinedRaw    = row.get("created_at");

        Map<String, Object> memberInfo = new java.util.LinkedHashMap<>();
        memberInfo.put("agencyCode",  agencyCode);
        memberInfo.put("agencyName",  agencyName);
        memberInfo.put("memberId",    memberId);
        memberInfo.put("nameMasked",  "회*원");   // PII 보호: 실제 이름 미저장 → 마스킹 고정값
        if (lastLoginRaw != null) {
            memberInfo.put("lastLoginAt", lastLoginRaw.toString());
        }
        if (joinedRaw != null) {
            memberInfo.put("joinedAt", joinedRaw.toString());
        }

        log.info("[MemberLookup] 회원 발견: agencyCode={} memberId={} correlationId={}",
                agencyCode, memberId, correlationId);
        return ResponseEntity.ok(Map.of("found", true, "member", memberInfo));
    }

    /**
     * POST /api/v1/members/link
     * Q-IM → 기관 계정 연결 확정 알림 (기관 측 레코드 업데이트).
     *
     * <p>Q-IM에서 auth_mean_mapping 추가 후 기관에도 연결 완료를 통보한다.
     * 기관은 이를 받아 agency_user에 qim_user_id 를 기록하거나 상태를 갱신한다.
     */
    @PostMapping("/link")
    public ResponseEntity<?> link(
            @RequestHeader(value = "X-Qim-Internal-Key", required = false) String internalKey,
            @RequestHeader(value = "X-Correlation-Id",   required = false) String correlationId,
            @RequestBody LinkRequest request) {

        if (!secureEquals(qimInternalKey, internalKey)) {
            return ResponseEntity.status(401)
                    .body(Map.of("error", "UNAUTHORIZED"));
        }

        String qimUserId = request.qimUserId();
        String targetAgencyCode = request.agencyCode();

        log.info("[MemberLink] 연결 완료 통보: qimUserId={} agencyCode={} correlationId={}",
                qimUserId, targetAgencyCode, correlationId);

        // PoC: agency_user 의 qim_user_id 업데이트 (실제 연결 기록)
        int updated = jdbcTemplate.update(
                "UPDATE agency_stub.agency_user " +
                "SET qim_user_id = ?, updated_at = ? " +
                "WHERE agency_subject_id = ? AND agency_code = ?",
                qimUserId, Instant.now(), request.identifierHash(), targetAgencyCode
        );

        return ResponseEntity.ok(Map.of(
                "linked",        true,
                "updatedRows",   updated,
                "agencyCode",    targetAgencyCode,
                "correlationId", asStr(correlationId)
        ));
    }

    // ── 내부 타입 ─────────────────────────────────────────────────────────────

    public record LookupRequest(String identifierHash, String agencyCode) {}
    public record LinkRequest(String qimUserId, String identifierHash, String agencyCode) {}

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    /** 타이밍 공격 방지 상수 시간 비교 (MessageDigest 해시 비교) */
    private boolean secureEquals(String expected, String actual) {
        if (expected == null || actual == null) return false;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] a = md.digest(expected.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] b = md.digest(actual.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.security.MessageDigest.isEqual(a, b);
        } catch (java.security.NoSuchAlgorithmException e) {
            return expected.equals(actual);
        }
    }

    private String asStr(Object o) {
        return o != null ? o.toString() : "";
    }
}
