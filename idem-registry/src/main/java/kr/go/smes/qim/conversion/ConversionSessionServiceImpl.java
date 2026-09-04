package kr.go.smes.qim.conversion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.go.smes.common.error.PlatformErrorCode;
import kr.go.smes.common.error.PlatformException;
import kr.go.smes.common.util.UuidV7;
import kr.go.smes.qim.infrastructure.jpa.entity.ConversionSessionJpaEntity;
import kr.go.smes.qim.infrastructure.jpa.repository.AuthMeanMappingJpaRepository;
import kr.go.smes.qim.infrastructure.jpa.repository.ConversionSessionJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 통합계정 전환 세션 서비스 구현체 (P3 — AgencyMemberLookupService 실제 연동 완료)
 *
 * <p>설계서 PPTX 2.1 프로세스:
 * 1. {@code initiate()} — 세션 생성 (기존 활성 세션 재사용 방어)
 * 2. {@code fetchCandidates()} — {@link AgencyMemberLookupService}로 68개 기관 CI 조회
 * 3. {@code selectAccounts()} — 사용자가 연결할 기관 선택
 * 4. {@code link()} — {@link AgencyMemberLookupService#performLinking} 으로 실제 연결
 * 5. {@code cancel()} / {@code expireStale()} — 취소·TTL 만료 처리
 *
 * <p><b>TTL</b>: 기본 30분 ({@code qim.conversion.session-ttl-minutes:30})
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversionSessionServiceImpl implements ConversionSessionService {

    private static final int DEFAULT_TTL_MINUTES = 30;

    private final ConversionSessionJpaRepository  sessionRepository;
    private final ObjectMapper                     objectMapper;
    /** 68개 기관 CI 기반 회원 조회/연결 서비스 */
    private final AgencyMemberLookupService        agencyMemberLookupService;
    /** PASS/CI 계열 identifierHash DB 조회 (M-02) */
    private final AuthMeanMappingJpaRepository     authMeanMappingRepository;

    // ── 퍼블릭 API ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public ConversionSessionResult initiate(String qimUserId, String correlationId) {
        log.info("[Conversion] 전환 세션 시작: qimUserId={}", qimUserId);

        // 기존 활성 세션 반환 (중복 시작 방지)
        var existing = sessionRepository.findActiveByUser(qimUserId, Instant.now());
        if (existing.isPresent()) {
            log.info("[Conversion] 기존 활성 세션 재사용: sessionId={}", existing.get().getSessionId());
            return toDomain(existing.get());
        }

        Instant now = Instant.now();
        ConversionSessionJpaEntity session = ConversionSessionJpaEntity.builder()
                .sessionId(UuidV7.generate())
                .qimUserId(qimUserId)
                .status(ConversionSessionState.INITIATED.name())
                .expiresAt(now.plus(DEFAULT_TTL_MINUTES, ChronoUnit.MINUTES))
                .createdAt(now)
                .updatedAt(now)
                .build();

        sessionRepository.save(session);
        log.info("[Conversion] 전환 세션 생성: sessionId={}", session.getSessionId());
        return toDomain(session);
    }

    @Override
    @Transactional
    public ConversionSessionResult fetchCandidates(String sessionId, String correlationId) {
        ConversionSessionJpaEntity session = findActiveSessionOrThrow(sessionId, correlationId);
        assertTransition(session, ConversionSessionState.MEMBERS_FETCHED, correlationId);

        // 유관 시스템 회원 조회 — identifierHash 우선, 없으면 qimUserId 자체를 해시로 사용
        String identifierHash = resolveIdentifierHash(session.getQimUserId());
        List<CandidateMember> candidates = agencyMemberLookupService.lookupByIdentifierHash(
                session.getQimUserId(), identifierHash, correlationId);

        session.setStatus(ConversionSessionState.MEMBERS_FETCHED.name());
        session.setCandidateMembersJson(serializeJson(candidates));
        sessionRepository.save(session);

        log.info("[Conversion] 후보 회원 조회 완료: sessionId={} count={}",
                sessionId, candidates.size());
        return toDomain(session);
    }

    @Override
    @Transactional
    public ConversionSessionResult selectAccounts(String sessionId,
                                                   List<String> selectedAgencies,
                                                   String correlationId) {
        ConversionSessionJpaEntity session = findActiveSessionOrThrow(sessionId, correlationId);
        assertTransition(session, ConversionSessionState.ACCOUNT_SELECTED, correlationId);

        session.setStatus(ConversionSessionState.ACCOUNT_SELECTED.name());
        session.setSelectedAgencyCodesJson(serializeJson(selectedAgencies));
        sessionRepository.save(session);

        log.info("[Conversion] 계정 선택 완료: sessionId={} selected={}",
                sessionId, selectedAgencies);
        return toDomain(session);
    }

    @Override
    @Transactional
    public ConversionSessionResult link(String sessionId, String correlationId) {
        ConversionSessionJpaEntity session = findActiveSessionOrThrow(sessionId, correlationId);
        assertTransition(session, ConversionSessionState.LINKING, correlationId);

        // LINKING 단계 전이
        session.setStatus(ConversionSessionState.LINKING.name());
        sessionRepository.save(session);

        // 실제 기관 API 연결 — AgencyMemberLookupService.performLinking()
        List<String> selectedCodes = deserializeStringList(session.getSelectedAgencyCodesJson());
        String identifierHash = resolveIdentifierHash(session.getQimUserId());
        List<String> linkedCodes = agencyMemberLookupService.performLinking(
                session.getQimUserId(), identifierHash, selectedCodes, correlationId);

        // COMPLETED 전이
        session.setStatus(ConversionSessionState.COMPLETED.name());
        session.setLinkedAgencyCodesJson(serializeJson(linkedCodes));
        sessionRepository.save(session);

        log.info("[Conversion] 계정 연결 완료: sessionId={} linked={}", sessionId, linkedCodes);
        return toDomain(session);
    }

    @Override
    @Transactional
    public ConversionSessionResult cancel(String sessionId, String reason, String correlationId) {
        ConversionSessionJpaEntity session = findSessionOrThrow(sessionId, correlationId);

        ConversionSessionState current = ConversionSessionState.valueOf(session.getStatus());
        if (!current.canTransitionTo(ConversionSessionState.CANCELLED)) {
            throw new PlatformException(
                    PlatformErrorCode.IM_CONVERSION_INVALID_STATE, correlationId);
        }

        session.setStatus(ConversionSessionState.CANCELLED.name());
        session.setCancelReason(reason);
        sessionRepository.save(session);

        log.info("[Conversion] 세션 취소: sessionId={} reason={}", sessionId, reason);
        return toDomain(session);
    }

    @Override
    @Transactional(readOnly = true)
    public ConversionSessionResult getSession(String sessionId, String correlationId) {
        return toDomain(findSessionOrThrow(sessionId, correlationId));
    }

    @Override
    @Scheduled(fixedDelayString = "${qim.conversion.expire-check-ms:60000}")
    @Transactional
    public int expireStale() {
        int count = sessionRepository.markExpiredBatch(Instant.now());
        if (count > 0) {
            log.info("[Conversion] 만료된 전환 세션 {}건 처리", count);
        }
        return count;
    }

    // ── 헬퍼 — identifierHash 해석 ───────────────────────────────────────────

    /**
     * qimUserId → identifierHash 해석 (M-02 구현 완료)
     *
     * <p><b>운영 로직</b>: {@code qim.auth_mean_mapping} 테이블에서 PASS/CI 계열
     * ({@code PASS}, {@code KICA}, {@code NICE}, {@code KCB}) ACTIVE 매핑의
     * {@code identifierHash}(= SHA-256(CI))를 우선순위 순으로 조회한다.
     *
     * <p><b>Fallback</b>: DB 매핑 미존재 시(최초 인증 전 세션, 테스트 환경 등)
     * qimUserId SHA-256 해시를 임시 식별자로 사용하며 WARN 로그를 남긴다.
     * 이 Fallback이 운영 트래픽에서 반복 발생하면 {@code auth_mean_mapping} 등록 누락을 의미한다.
     */
    private String resolveIdentifierHash(String qimUserId) {
        // ① 운영 DB에서 PASS/CI 계열 identifierHash 조회
        var dbHash = authMeanMappingRepository.findActivePassCiHash(qimUserId);
        if (dbHash.isPresent()) {
            log.debug("[Conversion] identifierHash DB 조회 성공: qimUserId={}", qimUserId);
            return dbHash.get();
        }

        // ② Fallback — PASS/CI 매핑 미등록 (최초 인증 전 / 테스트 환경)
        log.warn("[Conversion] PASS/CI identifierHash 미등록 — qimUserId SHA-256 Fallback 사용: qimUserId={} "
                + "→ auth_mean_mapping 등록 여부 확인 필요", qimUserId);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(qimUserId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256은 Java 명세상 항상 지원 — 발생 불가
            throw new IllegalStateException("SHA-256 알고리즘 지원 안 됨 (JVM 환경 이상)", e);
        }
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private ConversionSessionJpaEntity findActiveSessionOrThrow(String sessionId,
                                                                  String correlationId) {
        ConversionSessionJpaEntity session = findSessionOrThrow(sessionId, correlationId);
        if (Instant.now().isAfter(session.getExpiresAt())) {
            throw new PlatformException(PlatformErrorCode.IM_CONVERSION_EXPIRED, correlationId);
        }
        return session;
    }

    private ConversionSessionJpaEntity findSessionOrThrow(String sessionId, String correlationId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new PlatformException(
                        PlatformErrorCode.IM_CONVERSION_NOT_FOUND, correlationId));
    }

    private void assertTransition(ConversionSessionJpaEntity session,
                                   ConversionSessionState next, String correlationId) {
        ConversionSessionState current = ConversionSessionState.valueOf(session.getStatus());
        if (!current.canTransitionTo(next)) {
            log.warn("[Conversion] 유효하지 않은 상태 전이: {} → {} sessionId={}",
                    current, next, session.getSessionId());
            throw new PlatformException(
                    PlatformErrorCode.IM_CONVERSION_INVALID_STATE, correlationId);
        }
    }

    private ConversionSessionResult toDomain(ConversionSessionJpaEntity e) {
        return ConversionSessionResult.builder()
                .sessionId(e.getSessionId())
                .qimUserId(e.getQimUserId())
                .state(ConversionSessionState.valueOf(e.getStatus()))
                .candidateMembers(deserializeCandidates(e.getCandidateMembersJson()))
                .selectedAgencyCodes(deserializeStringList(e.getSelectedAgencyCodesJson()))
                .linkedAgencyCodes(deserializeStringList(e.getLinkedAgencyCodesJson()))
                .expiresAt(e.getExpiresAt())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }

    private String serializeJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("[Conversion] JSON 직렬화 실패: {}", e.getMessage());
            return "[]";
        }
    }

    private List<CandidateMember> deserializeCandidates(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private List<String> deserializeStringList(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
