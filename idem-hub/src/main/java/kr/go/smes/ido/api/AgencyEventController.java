package kr.go.smes.ido.api;

import kr.go.smes.common.util.CorrelationIdHolder;
import kr.go.smes.ido.api.dto.AgencyEventListResponse;
import kr.go.smes.ido.config.HandoffAgencyKeyInterceptor;
import kr.go.smes.ido.webhook.AgencyEventQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;

/**
 * 유관기관 이벤트 폴링 API
 *
 * <p>설계서 §P1-06 — Kafka에 직접 접속할 수 없는 유관기관이
 * HTTP 폴링으로 자신에게 발생한 이벤트를 가져가는 엔드포인트.
 *
 * <p><b>인증</b>:
 * {@link HandoffAgencyKeyInterceptor} 가 {@code X-Agency-Code} + {@code X-Agency-Key} 헤더를
 * 사전 검증하며, 검증된 agencyCode를 Request Attribute
 * ({@link HandoffAgencyKeyInterceptor#ATTR_VALIDATED_AGENCY_CODE})에 저장.
 * 컨트롤러는 이 Attribute만 사용 — rawKey는 절대 접근하지 않음.
 *
 * <p><b>데이터 소스</b>: {@code ido.webhook_dispatch_outbox}
 * WebhookDispatcherService가 이미 Kafka 이벤트를 처리·마스킹하여 저장한 레코드를 조회.
 *
 * <p><b>엔드포인트 목록</b>:
 * <ul>
 *   <li>{@code GET  /api/v1/agency/events}              — 이벤트 폴링</li>
 *   <li>{@code POST /api/v1/agency/events/{dispatchId}/read} — 읽음 처리</li>
 * </ul>
 *
 * <p><b>폴링 권장 패턴</b>:
 * <pre>
 * 1. GET /api/v1/agency/events?limit=50
 * 2. hasMore=true 이면 계속 폴링 (since 파라미터로 커서 이동)
 * 3. 이벤트 수신 후 POST /{dispatchId}/read 로 읽음 처리
 * 4. 이벤트가 없을 때까지 반복 (권장 폴링 주기: 30~60초)
 * </pre>
 *
 * @see AgencyEventQueryService
 * @see HandoffAgencyKeyInterceptor
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/agency")
@RequiredArgsConstructor
public class AgencyEventController {

    private static final int DEFAULT_LIMIT =  20;
    private static final int MAX_LIMIT     = 100;

    private final AgencyEventQueryService agencyEventQueryService;

    // ══════════════════════════════════════════════════════════════════════
    // GET /api/v1/agency/events — 이벤트 폴링
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 기관 이벤트 폴링
     *
     * <p>기관에게 발생한 이벤트 목록을 반환.
     * 이벤트는 {@code ido.webhook_dispatch_outbox}에서 조회되며,
     * status가 PENDING 또는 DISPATCHED인 이벤트만 반환.
     *
     * <p><b>요청 헤더</b>:
     * <ul>
     *   <li>{@code X-Agency-Code} (필수): 기관 코드</li>
     *   <li>{@code X-Agency-Key}  (필수): 기관 API Key (인터셉터가 검증)</li>
     *   <li>{@code X-Correlation-Id} (선택): 추적 ID</li>
     * </ul>
     *
     * <p><b>쿼리 파라미터</b>:
     * <ul>
     *   <li>{@code limit}: 최대 반환 건수 (기본 20, 최대 100)</li>
     *   <li>{@code eventType}: 이벤트 타입 필터 (선택, e.g. HANDOFF_ISSUED)</li>
     *   <li>{@code since}: 이 시각 이후 이벤트만 조회 (ISO-8601, 선택)</li>
     * </ul>
     *
     * <p><b>응답</b>:
     * <ul>
     *   <li>200 OK: 이벤트 목록 (빈 목록도 200)</li>
     *   <li>401 Unauthorized: X-Agency-Key 검증 실패 (인터셉터에서 처리)</li>
     *   <li>400 Bad Request: limit 범위 초과 (1~100)</li>
     * </ul>
     *
     * @param request     HttpServletRequest (인터셉터가 저장한 agencyCode 추출용)
     * @param correlationId 추적 ID (선택)
     * @param limit       최대 반환 건수 (기본 20)
     * @param eventType   이벤트 타입 필터 (선택)
     * @param since       since 커서 (ISO-8601 문자열, 선택)
     * @return 이벤트 목록 응답
     */
    @GetMapping("/events")
    public ResponseEntity<AgencyEventListResponse> pollEvents(
            HttpServletRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String since) {

        // ① 인터셉터에서 검증 완료된 agencyCode 추출
        String agencyCode = extractValidatedAgencyCode(request);

        // ② CorrelationId 설정
        String cid = correlationId != null ? correlationId : CorrelationIdHolder.generate();
        CorrelationIdHolder.set(cid);

        // ③ limit 범위 검증
        if (limit < 1 || limit > MAX_LIMIT) {
            log.warn("[AgencyEventCtrl] limit 범위 초과: limit={} agencyCode={} cid={}", limit, agencyCode, cid);
            return ResponseEntity.badRequest().build();
        }

        // ④ since 파싱 (ISO-8601 문자열 → Instant)
        Instant sinceInstant = parseSince(since, agencyCode, cid);

        log.info("[AgencyEventCtrl] 이벤트 폴링 요청: agencyCode={} limit={} eventType={} since={} cid={}",
                agencyCode, limit, eventType, since, cid);

        // ⑤ 이벤트 조회
        AgencyEventListResponse response = agencyEventQueryService.queryEvents(
                agencyCode, limit, eventType, sinceInstant
        );

        log.info("[AgencyEventCtrl] 이벤트 폴링 완료: agencyCode={} count={} hasMore={} cid={}",
                agencyCode, response.getCount(), response.isHasMore(), cid);

        return ResponseEntity.ok(response);
    }

    // ══════════════════════════════════════════════════════════════════════
    // POST /api/v1/agency/events/{dispatchId}/read — 읽음 처리
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 이벤트 읽음 처리 (mark-as-read)
     *
     * <p>기관이 폴링으로 수신한 이벤트를 처리 완료 후 읽음으로 표시.
     * PENDING → DISPATCHED 상태 변경 (멱등: 이미 DISPATCHED이면 무시).
     *
     * <p><b>보안</b>: 자신의 agencyCode에 속한 dispatch_id만 변경 가능.
     * 타 기관의 dispatch_id 지정 시 404 반환 (소유권 노출 방지).
     *
     * @param request      HttpServletRequest (agencyCode 추출용)
     * @param correlationId 추적 ID (선택)
     * @param dispatchId   읽음 처리할 dispatch_id
     * @return 204 No Content (성공 또는 이미 처리), 404 Not Found (존재하지 않거나 권한 없음)
     */
    @PostMapping("/events/{dispatchId}/read")
    public ResponseEntity<Map<String, Object>> markAsRead(
            HttpServletRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @PathVariable String dispatchId) {

        // ① 검증된 agencyCode 추출
        String agencyCode = extractValidatedAgencyCode(request);

        String cid = correlationId != null ? correlationId : CorrelationIdHolder.generate();
        CorrelationIdHolder.set(cid);

        log.info("[AgencyEventCtrl] mark-as-read 요청: agencyCode={} dispatchId={} cid={}",
                agencyCode, dispatchId, cid);

        boolean updated = agencyEventQueryService.markAsRead(dispatchId, agencyCode);

        if (updated) {
            log.info("[AgencyEventCtrl] mark-as-read 성공: agencyCode={} dispatchId={} cid={}",
                    agencyCode, dispatchId, cid);
            return ResponseEntity.noContent().build();
        } else {
            // 이미 처리됐거나 해당 기관 소유가 아님 — 404로 동일 처리 (정보 노출 방지)
            log.debug("[AgencyEventCtrl] mark-as-read 미처리 (이미 완료 or 권한 없음): " +
                    "agencyCode={} dispatchId={} cid={}", agencyCode, dispatchId, cid);
            return ResponseEntity.notFound().build();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 내부 헬퍼
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 인터셉터가 Request Attribute에 저장한 검증된 agencyCode 추출
     *
     * @throws IllegalStateException 인터셉터 미등록 또는 설정 오류
     */
    private String extractValidatedAgencyCode(HttpServletRequest request) {
        Object attr = request.getAttribute(HandoffAgencyKeyInterceptor.ATTR_VALIDATED_AGENCY_CODE);
        if (attr == null) {
            // 인터셉터가 적용되지 않은 경우 (설정 오류) — 500으로 처리
            throw new IllegalStateException(
                    "[AgencyEventCtrl] validatedAgencyCode Attribute 없음 — " +
                    "HandoffAgencyKeyInterceptor 미등록 확인 필요"
            );
        }
        return attr.toString();
    }

    /**
     * since 파라미터 파싱 (ISO-8601 문자열 → Instant)
     * 파싱 실패 시 null 반환 (since 무시, 전체 조회)
     */
    private Instant parseSince(String since, String agencyCode, String cid) {
        if (since == null || since.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(since);
        } catch (Exception e) {
            log.warn("[AgencyEventCtrl] since 파싱 실패, 전체 조회로 처리: since={} agencyCode={} cid={} error={}",
                    since, agencyCode, cid, e.getMessage());
            return null;
        }
    }
}
