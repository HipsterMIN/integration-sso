package kr.go.smes.qim.conversion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.go.smes.common.error.PlatformErrorCode;
import kr.go.smes.common.error.PlatformException;
import kr.go.smes.common.util.UuidV7;
import kr.go.smes.qim.infrastructure.jpa.entity.ConversionSessionJpaEntity;
import kr.go.smes.qim.infrastructure.jpa.repository.ConversionSessionJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 통합계정 전환 세션 서비스 구현체 (P2 §12.1)
 *
 * <p>AgencyMemberLookupService는 현재 stub 구현으로 빈 목록 반환.
 * 실제 연동 시 PPTX 2.1 프로세스에 따라 각 기관 API 호출로 교체 예정.
 *
 * <p><b>TTL</b>: 기본 30분 ({@code qim.conversion.session-ttl-minutes:30})
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversionSessionServiceImpl implements ConversionSessionService {

    private static final int DEFAULT_TTL_MINUTES = 30;

    private final ConversionSessionJpaRepository sessionRepository;
    private final ObjectMapper                    objectMapper;

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

        // 유관 시스템 회원 조회 (현재 stub — 실제 연동 시 AgencyMemberLookupService 호환)
        List<CandidateMember> candidates = lookupCandidateMembers(
                session.getQimUserId(), correlationId);

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

        // 실제 기관 매핑 연결 (현재 stub — auth_mean_mapping 업데이트)
        List<String> selectedCodes = deserializeStringList(session.getSelectedAgencyCodesJson());
        List<String> linkedCodes   = performLinking(session.getQimUserId(),
                selectedCodes, correlationId);

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

    // ── stub 구현 (실제 연동 시 교체) ────────────────────────────────────────

    /**
     * 유관 시스템 회원 조회 stub
     * 실제 구현 시 AgencyMemberLookupService(CI 기반 68개 기관 API 조회)로 교체.
     */
    private List<CandidateMember> lookupCandidateMembers(String qimUserId, String correlationId) {
        log.debug("[Conversion][Stub] 유관 시스템 회원 조회 (stub): qimUserId={}", qimUserId);
        return new ArrayList<>(); // TODO(P3): AgencyMemberLookupService 실제 연동
    }

    /**
     * 계정 연결 stub
     * 실제 구현 시 각 기관별 auth_mean_mapping 추가 + 기관 API 연결 호출로 교체.
     */
    private List<String> performLinking(String qimUserId, List<String> selectedCodes,
                                          String correlationId) {
        log.debug("[Conversion][Stub] 계정 연결 (stub): qimUserId={} codes={}", qimUserId, selectedCodes);
        return new ArrayList<>(selectedCodes); // TODO(P3): 실제 기관 API 연결
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
