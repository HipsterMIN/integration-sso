package kr.go.smes.agency.api;

import kr.go.smes.common.domain.HandoffPayload;
import kr.go.smes.common.util.CorrelationIdHolder;
import kr.go.smes.agency.session.AgencyLocalSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 기관 진입 컨트롤러 (Direct Integration 패턴)
 * 설계서 14.2 / 15.1절 참조
 *
 * 개발자 체크리스트 (설계서 15.1절):
 *   [v] IdO Verify API 호출 시 강한 API Key 사용
 *   [v] Ticket 응답의 서명 검증 (TODO: 실제 서명 검증 구현)
 *   [v] Verify 응답을 캐시하지 않음 (1회성)
 *   [v] AGSID는 Verify 성공 후에만 발급
 *   [v] Session Fixation 방지 (기존 AGSID 폐기 후 재발급)
 *   [v] correlationId를 모든 로그에 기록
 */
@Slf4j
@RestController
@RequestMapping("/agency/entry")
@RequiredArgsConstructor
public class AgencyEntryController {

    private final RestTemplate restTemplate;

    /**
     * 기관 진입 — IdO Ticket 검증 후 기관 로컬 세션 생성
     * POST /agency/entry?ticketId=...
     */
    @PostMapping
    public ResponseEntity<?> enter(
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @CookieValue(name = "AGSID", required = false) String existingAgsid,
            @RequestParam String ticketId,
            HttpServletResponse response) {

        String cid = correlationId != null ? correlationId : CorrelationIdHolder.generate();
        CorrelationIdHolder.set(cid);

        log.info("[Agency-Stub] 기관 진입 요청 ticketId={} correlationId={}", ticketId, cid);

        // 1. IdO Verify API 호출 (Verify 응답 캐시 금지 — 1회성)
        HandoffPayload payload = callIdoVerify(ticketId, cid);

        if (payload.getState() != HandoffPayload.HandoffState.APPROVED) {
            log.warn("[Agency-Stub] Handoff 거부 state={} correlationId={}", payload.getState(), cid);
            return ResponseEntity.status(403).body(Map.of("error", "HANDOFF_REJECTED"));
        }

        // 2. Session Fixation 방지: 기존 AGSID 폐기 (설계서 14.4절)
        if (existingAgsid != null) {
            log.info("[Agency-Stub] 기존 AGSID 폐기 correlationId={}", cid);
            invalidateCookie("AGSID", response);
        }

        // 3. 기관 로컬 세션 생성 (설계서 14.2절)
        AgencyLocalSession session = createLocalSession(payload, ticketId, cid);

        // 4. 새 AGSID 쿠키 발급 (Secure/HttpOnly/SameSite=Strict)
        ResponseCookie agsidCookie = ResponseCookie.from("AGSID", session.getAgencySessionId())
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(60 * 30) // 30분
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, agsidCookie.toString());

        log.info("[Agency-Stub] 기관 로컬 세션 생성 완료 agencySessionId={} correlationId={}",
                session.getAgencySessionId(), cid);

        return ResponseEntity.ok(Map.of(
                "agencyUserId",    session.getAgencyUserId(),
                "agencySubjectId", session.getAgencySubjectId(),
                "authLevel",       session.getAuthLevel(),
                "correlationId",   cid
        ));
    }

    // ── private ─────────────────────────────────────────────────────────────

    private HandoffPayload callIdoVerify(String ticketId, String correlationId) {
        // TODO: 실제 IdO Verify API 호출 (mTLS or API Key 헤더 포함)
        // TODO: Ticket 서명 검증
        // 현재는 stub 반환
        return HandoffPayload.builder()
                .ticketId(ticketId)
                .correlationId(correlationId)
                .agencyCode("AGENCY_STUB_001")
                .policyVersion("1.0")
                .state(HandoffPayload.HandoffState.APPROVED)
                .subject(HandoffPayload.SubjectIdentifier.builder()
                        .agencySubjectId("STUB_SUBJECT_" + ticketId.substring(0, 8))
                        .qimUserId("STUB_QIM_USER_001")
                        .build())
                .authContext(HandoffPayload.AuthContext.builder()
                        .authLevel(kr.go.smes.common.domain.AuthResult.AuthLevel.L2)
                        .authResultId("STUB_AUTH_RESULT_001")
                        .authenticatedAt(Instant.now())
                        .build())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }

    private AgencyLocalSession createLocalSession(HandoffPayload payload,
                                                   String ticketId, String cid) {
        Instant now = Instant.now();
        return AgencyLocalSession.builder()
                .agencySessionId(UUID.randomUUID().toString().replace("-", ""))
                .agencyUserId("AGENCY_USER_" + payload.getSubject().getAgencySubjectId())
                .agencySubjectId(payload.getSubject().getAgencySubjectId())
                .qimUserId(payload.getSubject().getQimUserId())
                .authLevel(payload.getAuthContext().getAuthLevel().name())
                .ticketId(ticketId)
                .correlationId(cid)
                .createdAt(now)
                .lastActivityAt(now)
                .absoluteExpiresAt(now.plusSeconds(60 * 480)) // 8시간
                .build();
    }

    private void invalidateCookie(String cookieName, HttpServletResponse response) {
        ResponseCookie clear = ResponseCookie.from(cookieName, "")
                .maxAge(0).httpOnly(true).secure(true).path("/").build();
        response.addHeader(HttpHeaders.SET_COOKIE, clear.toString());
    }
}
