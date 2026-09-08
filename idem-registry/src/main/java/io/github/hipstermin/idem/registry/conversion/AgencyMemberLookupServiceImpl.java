package io.github.hipstermin.idem.registry.conversion;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * AgencyMemberLookupService 실제 구현체 — 68개 기관 CI 기반 회원 조회
 *
 * <p>설계서 PPTX 2.1 프로세스:
 * <ol>
 *   <li>{@code lookupByIdentifierHash()} — 활성 기관 전체에 병렬 HTTP 조회</li>
 *   <li>개별 기관 타임아웃({@code qim.agency.lookup-timeout-ms}) 내 응답 없으면 SKIP</li>
 *   <li>부분 실패 허용 — 응답받은 기관 중 회원 존재 기관만 CandidateMember 목록 구성</li>
 *   <li>{@code performLinking()} — 선택된 기관에 연결 완료 통보 후 성공 코드 반환</li>
 * </ol>
 *
 * <h3>병렬 처리 전략</h3>
 * <ul>
 *   <li>Virtual Thread Executor(JDK 21) — I/O 블로킹 최소화, 기관 수만큼 스레드 생성</li>
 *   <li>전체 데드라인({@code qim.agency.total-lookup-timeout-ms:15000}) 기준 절대 시각으로 관리.
 *       각 Future.get() 에 남은 시간만큼만 대기하여 N개 Future의 타임아웃이 누적되지 않도록 보장.</li>
 *   <li>Executor는 반드시 try-finally로 {@code shutdownNow()} 보장 — 예외 시에도 스레드 누수 없음</li>
 * </ul>
 *
 * <h3>기관 API 규격 (agency-stub)</h3>
 * <pre>
 *   POST {baseUrl}/api/v1/members/lookup
 *   Headers: X-Qim-Internal-Key, X-Correlation-Id
 *   Body: { "identifierHash": "...", "agencyCode": "..." }
 *   Response: { "found": bool, "member": { ... } }
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgencyMemberLookupServiceImpl implements AgencyMemberLookupService {

    private final AgencyRegistry  agencyRegistry;
    private final RestTemplate    restTemplate;
    private final ObjectMapper    objectMapper;

    /** Q-IM → 기관 내부 키 (agency-stub X-Qim-Internal-Key 헤더) */
    @Value("${qim.agency.internal-key:qim-internal-key-dev-001}")
    private String qimInternalKey;

    /** 전체 조회 제한 시간 (ms) — 이 시간 내에 모든 기관 응답을 수집 */
    @Value("${qim.agency.total-lookup-timeout-ms:15000}")
    private int totalTimeoutMs;

    // ── 회원 조회 ─────────────────────────────────────────────────────────────

    @Override
    public List<CandidateMember> lookupByIdentifierHash(String qimUserId,
                                                         String identifierHash,
                                                         String correlationId) {
        List<AgencyRegistration> agencies = agencyRegistry.getActiveAgencies();
        log.info("[AgencyLookup] 기관 회원 조회 시작: qimUserId={} agencies={} correlationId={}",
                qimUserId, agencies.size(), correlationId);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount    = new AtomicInteger(0);

        // Virtual Thread Executor — JDK 21 지원, I/O 집약적 작업에 최적
        // try-finally 보장: 예외 발생 시에도 shutdownNow()로 스레드 자원 반납
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        // 모든 Future를 먼저 제출
        List<Future<Optional<CandidateMember>>> futures;
        try {
            futures = agencies.stream()
                    .map(agency -> executor.submit(
                            () -> lookupSingle(agency, identifierHash, correlationId,
                                    successCount, failCount)))
                    .toList();
        } finally {
            // 새 작업 제출은 막고, 진행 중인 작업은 계속 실행
            executor.shutdown();
        }

        // 절대 데드라인 기준으로 남은 시간을 계산하여 각 Future에 적용
        // → N개 Future.get(totalTimeoutMs) 순차 호출 시 타임아웃이 누적되는 문제 해소
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(totalTimeoutMs);

        List<CandidateMember> candidates = new ArrayList<>();
        for (Future<Optional<CandidateMember>> future : futures) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                log.warn("[AgencyLookup] 전체 데드라인 초과 — 남은 기관 결과 스킵 correlationId={}",
                        correlationId);
                future.cancel(true);
                failCount.incrementAndGet();
                continue;
            }
            try {
                future.get(remainingNanos, TimeUnit.NANOSECONDS)
                      .ifPresent(candidates::add);
            } catch (TimeoutException e) {
                log.warn("[AgencyLookup] 데드라인 초과 — Future 취소 correlationId={}", correlationId);
                future.cancel(true);
                failCount.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[AgencyLookup] 인터럽트 — 조회 중단 correlationId={}", correlationId);
                // 인터럽트 시 남은 Future 모두 취소
                futures.forEach(f -> f.cancel(true));
                break;
            } catch (ExecutionException e) {
                log.warn("[AgencyLookup] 기관 조회 ExecutionException: {} correlationId={}",
                        e.getCause() != null ? e.getCause().getMessage() : e.getMessage(),
                        correlationId);
                failCount.incrementAndGet();
            }
        }

        // 타임아웃 초과로 미처 취소되지 않은 Future 모두 강제 취소
        executor.shutdownNow();

        log.info("[AgencyLookup] 조회 완료: qimUserId={} found={} success={} fail={} correlationId={}",
                qimUserId, candidates.size(), successCount.get(), failCount.get(), correlationId);
        return candidates;
    }

    // ── 계정 연결 ─────────────────────────────────────────────────────────────

    @Override
    public List<String> performLinking(String qimUserId,
                                        String identifierHash,
                                        List<String> selectedAgencyCodes,
                                        String correlationId) {
        log.info("[AgencyLinking] 계정 연결 시작: qimUserId={} agencies={} correlationId={}",
                qimUserId, selectedAgencyCodes, correlationId);

        List<AgencyRegistration> agencies = agencyRegistry.getActiveAgencies().stream()
                .filter(a -> selectedAgencyCodes.contains(a.getAgencyCode()))
                .toList();

        List<String> linkedCodes = new ArrayList<>();

        for (AgencyRegistration agency : agencies) {
            try {
                boolean ok = linkSingle(agency, qimUserId, identifierHash, correlationId);
                if (ok) {
                    linkedCodes.add(agency.getAgencyCode());
                    log.info("[AgencyLinking] 연결 성공: agencyCode={} qimUserId={} correlationId={}",
                            agency.getAgencyCode(), qimUserId, correlationId);
                }
            } catch (Exception e) {
                // 부분 실패 허용 — 실패 기관은 건너뜀
                log.warn("[AgencyLinking] 연결 실패 (건너뜀): agencyCode={} error={} correlationId={}",
                        agency.getAgencyCode(), e.getMessage(), correlationId);
            }
        }

        log.info("[AgencyLinking] 연결 완료: linked={}/{} correlationId={}",
                linkedCodes.size(), selectedAgencyCodes.size(), correlationId);
        return linkedCodes;
    }

    // ── 단일 기관 조회 ────────────────────────────────────────────────────────

    private Optional<CandidateMember> lookupSingle(AgencyRegistration agency,
                                                     String identifierHash,
                                                     String correlationId,
                                                     AtomicInteger successCount,
                                                     AtomicInteger failCount) {
        String url = agency.getBaseUrl() + "/api/v1/members/lookup";
        try {
            HttpHeaders headers = buildHeaders(correlationId);
            Map<String, String> body = Map.of(
                    "identifierHash", identifierHash,
                    "agencyCode",     agency.getAgencyCode()
            );

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    String.class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> resp = objectMapper.readValue(
                        response.getBody(), new TypeReference<>() {});

                Boolean found = (Boolean) resp.get("found");
                if (Boolean.TRUE.equals(found)) {
                    CandidateMember member = parseMember(resp, agency);
                    successCount.incrementAndGet();
                    return Optional.of(member);
                }
                successCount.incrementAndGet();
                return Optional.empty();
            }
        } catch (ResourceAccessException e) {
            log.debug("[AgencyLookup] 기관 접근 불가 (타임아웃/연결거부): agencyCode={} url={} correlationId={}",
                    agency.getAgencyCode(), url, correlationId);
            failCount.incrementAndGet();
        } catch (HttpClientErrorException e) {
            log.debug("[AgencyLookup] 기관 4xx 오류: agencyCode={} status={} correlationId={}",
                    agency.getAgencyCode(), e.getStatusCode(), correlationId);
            failCount.incrementAndGet();
        } catch (HttpServerErrorException e) {
            log.warn("[AgencyLookup] 기관 5xx 오류: agencyCode={} status={} correlationId={}",
                    agency.getAgencyCode(), e.getStatusCode(), correlationId);
            failCount.incrementAndGet();
        } catch (Exception e) {
            log.warn("[AgencyLookup] 기관 조회 예외: agencyCode={} error={} correlationId={}",
                    agency.getAgencyCode(), e.getMessage(), correlationId);
            failCount.incrementAndGet();
        }
        return Optional.empty();
    }

    // ── 단일 기관 연결 ────────────────────────────────────────────────────────

    private boolean linkSingle(AgencyRegistration agency, String qimUserId,
                                String identifierHash, String correlationId) {
        String url = agency.getBaseUrl() + "/api/v1/members/link";
        HttpHeaders headers = buildHeaders(correlationId);
        Map<String, String> body = Map.of(
                "qimUserId",      qimUserId,
                "identifierHash", identifierHash,
                "agencyCode",     agency.getAgencyCode()
        );

        ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class
        );
        return response.getStatusCode().is2xxSuccessful();
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private HttpHeaders buildHeaders(String correlationId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Qim-Internal-Key", qimInternalKey);
        if (correlationId != null) {
            headers.set("X-Correlation-Id", correlationId);
        }
        return headers;
    }

    @SuppressWarnings("unchecked")
    private CandidateMember parseMember(Map<String, Object> resp, AgencyRegistration agency) {
        Object memberObj = resp.get("member");
        Map<String, Object> m = memberObj instanceof Map
                ? (Map<String, Object>) memberObj
                : Map.of();

        Instant lastLoginAt = parseInstant(m.get("lastLoginAt"));
        Instant joinedAt    = parseInstant(m.get("joinedAt"));

        return CandidateMember.builder()
                .agencyCode(agency.getAgencyCode())
                .agencyName(agency.getAgencyName())
                .memberId(asStr(m.get("memberId")))
                .nameMasked(asStr(m.getOrDefault("nameMasked", "회*원")))
                .lastLoginAt(lastLoginAt)
                .joinedAt(joinedAt)
                .build();
    }

    private Instant parseInstant(Object o) {
        if (o == null) return null;
        try {
            return Instant.parse(o.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private String asStr(Object o) {
        return o != null ? o.toString() : "";
    }
}
