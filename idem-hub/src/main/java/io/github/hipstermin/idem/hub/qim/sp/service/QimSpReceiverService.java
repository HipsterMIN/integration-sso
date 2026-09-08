package io.github.hipstermin.idem.hub.qim.sp.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.util.ApiKeyHashUtil;
import io.github.hipstermin.idem.common.util.UuidV7;
import io.github.hipstermin.idem.hub.fe.session.FeSessionService;
import io.github.hipstermin.idem.hub.infrastructure.outbox.IdoOutboxRepository;
import io.github.hipstermin.idem.hub.qim.crypto.AesSharedKeyDecryptor;
import io.github.hipstermin.idem.hub.qim.sp.api.dto.*;
import io.github.hipstermin.idem.hub.qim.sp.domain.InstMbrIdMapping;
import io.github.hipstermin.idem.hub.qim.sp.infrastructure.InstMbrIdMappingRepository;
import io.github.hipstermin.idem.hub.qim.sp.infrastructure.SpReceiverIdempotencyStore;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Q-IM SP 수신 API 핵심 서비스
 *
 * Q-IM 명세서 v1.52 §4 — SP가 구현하는 수신 API 처리 로직
 *
 * 처리 원칙:
 *   1. API Key 검증 → 멱등성 확인 → AES 복호화 → 매핑 처리 → Outbox 발행
 *   2. 멱등 재호출 시 DB 재처리 없이 저장된 응답 즉시 반환
 *   3. Outbox를 통한 비동기 Kafka 발행으로 응답 SLA < 500ms 목표
 *
 * @see <a href="docs/qim-sp-receiver-api-spec.md">SP 수신 API 명세서</a>
 * @see <a href="docs/qim-ido-integration-architecture.md">Q-IM↔IdO 아키텍처</a>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QimSpReceiverService {

    private static final String TOPIC_QIM_USER_EVENTS = "qim.user.events";

    // ── 이벤트 타입 (qim_outbox 명세서 QIM-OUTBOX-SPEC-001 기준) ─────────────
    // 기업회원 전환 (구 QIM_MEMBER_TRANSFERRED + CORPORATE)
    static final String EVENT_BIZ_MEMBER_CONVERTED      = "BIZ_MEMBER_CONVERTED";
    // 기업회원 신규 등록 (구 QIM_MEMBER_REGISTERED + CORPORATE)
    static final String EVENT_BIZ_MEMBER_REGISTERED     = "BIZ_MEMBER_REGISTERED";
    // 개인회원 전환 (구 QIM_MEMBER_TRANSFERRED + PERSONAL)
    static final String EVENT_PERSONAL_MEMBER_CONVERTED = "PERSONAL_MEMBER_CONVERTED";
    // 개인회원 신규 등록 (구 QIM_MEMBER_REGISTERED + PERSONAL)
    static final String EVENT_PERSONAL_MEMBER_REGISTERED = "PERSONAL_MEMBER_REGISTERED";
    // 회원 탈퇴 (명칭 동일, 접두사만 제거)
    static final String EVENT_MEMBER_WITHDRAWN          = "MEMBER_WITHDRAWN";

    // Endpoint codes (멱등성 테이블 구분용)
    private static final String ENDPOINT_QUERY    = "QUERY";
    private static final String ENDPOINT_REGISTER = "REGISTER";
    private static final String ENDPOINT_WITHDRAW = "WITHDRAW";

    private final InstMbrIdMappingRepository mappingRepository;
    private final SpReceiverIdempotencyStore idempotencyStore;
    private final AesSharedKeyDecryptor aesDecryptor;
    private final IdoOutboxRepository outboxRepository;
    private final FeSessionService feSessionService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    @Value("${ido.qim.inbound-api-key-hash:CHANGEME}")
    private String inboundApiKeyHash;

    // ── MEMBER_QUERY ─────────────────────────────────────────────────────────

    /**
     * 회원 조회 수신 처리 (MEMBER_QUERY)
     * Q-IM이 전환/탈퇴 전 SP에 회원 존재 여부를 확인한다.
     */
    public QimSpResponse<QimSpResponse.QueryData> handleMemberQuery(
            QimSpMemberQueryRequest request,
            String idempotencyKey,
            String correlationId) {

        // 1. 멱등성 확인
        Optional<SpReceiverIdempotencyStore.StoredResponse> cached =
                idempotencyStore.find(idempotencyKey);
        if (cached.isPresent()) {
            log.info("[QIM-SP] QUERY 멱등 재호출 key={}", idempotencyKey);
            return deserializeResponse(cached.get().responseJson());
        }

        // 2. 조회 처리
        Optional<InstMbrIdMapping> found;
        if (request.isPersonal() && request.getEncCi() != null) {
            String plainCi = safeDecrypt(request.getEncCi(), correlationId);
            String identifierHash = aesDecryptor.computeIdentifierHash(plainCi);
            found = mappingRepository.findByIdentifierHash(identifierHash);
        } else if (request.isCorporate() && request.getBrno() != null) {
            String identifierHash = aesDecryptor.computeIdentifierHashFromBrno(request.getBrno());
            found = mappingRepository.findByIdentifierHash(identifierHash);
        } else {
            log.warn("[QIM-SP] QUERY 요청 형식 오류 — encCi/brno 없음 correlationId={}", correlationId);
            return QimSpResponse.error("SP_INPUT_INVALID", "encCi 또는 brno가 필요합니다.");
        }

        // 3. 응답 구성 (탈퇴 회원도 exists=false로 처리)
        boolean exists = found.isPresent() && !found.get().isWithdrawn();
        String instMbrId = exists ? found.get().getInstMbrId() : null;

        QimSpResponse<QimSpResponse.QueryData> response = QimSpResponse.ok(
                QimSpResponse.QueryData.builder()
                        .exists(exists)
                        .instMbrId(instMbrId)
                        .build(),
                "조회 완료");

        // 4. 멱등성 저장
        idempotencyStore.save(idempotencyKey, ENDPOINT_QUERY, 200,
                serializeResponse(response), correlationId);

        log.info("[QIM-SP] QUERY 처리 완료 exists={} instMbrId={} correlationId={}",
                exists, instMbrId, correlationId);
        return response;
    }

    // ── MEMBER_REGISTER ──────────────────────────────────────────────────────

    /**
     * 회원 등록/전환 수신 처리 (MEMBER_REGISTER)
     * Q-IM이 회원을 저장한 후 SP(=IdO)에 통보한다.
     *
     * <p><b>이벤트 타입 결정 규칙 (QIM-OUTBOX-SPEC-001)</b>:
     * <pre>
     *   isTransfer=true  + isCorporate=true  → BIZ_MEMBER_CONVERTED
     *   isTransfer=false + isCorporate=true  → BIZ_MEMBER_REGISTERED
     *   isTransfer=true  + isCorporate=false → PERSONAL_MEMBER_CONVERTED
     *   isTransfer=false + isCorporate=false → PERSONAL_MEMBER_REGISTERED
     * </pre>
     */
    @Transactional
    public QimSpResponse<QimSpResponse.RegisterData> handleMemberRegister(
            QimSpMemberRegisterRequest request,
            String idempotencyKey,
            String correlationId) {

        // 1. 멱등성 확인
        Optional<SpReceiverIdempotencyStore.StoredResponse> cached =
                idempotencyStore.find(idempotencyKey);
        if (cached.isPresent()) {
            log.info("[QIM-SP] REGISTER 멱등 재호출 key={}", idempotencyKey);
            return deserializeResponse(cached.get().responseJson());
        }

        // 2. AES 복호화 → identifierHash
        String identifierHash = null;
        if (request.getEncCi() != null) {
            String plainCi = safeDecrypt(request.getEncCi(), correlationId);
            identifierHash = aesDecryptor.computeIdentifierHash(plainCi);
        } else if (request.isCorporate() && request.getBrno() != null) {
            identifierHash = aesDecryptor.computeIdentifierHashFromBrno(request.getBrno());
        }

        // 3. instMbrId 결정 (mbrUuid 기반 중복 확인)
        String effectiveMbrUuid = request.getEffectiveMbrUuid();
        Optional<InstMbrIdMapping> existing = (effectiveMbrUuid != null)
                ? mappingRepository.findByMbrUuid(effectiveMbrUuid)
                : Optional.empty();

        String instMbrId;
        Instant registeredAt;

        if (existing.isPresent()) {
            // 이미 등록된 회원 — instMbrId 재사용 (TRANSFER 재등록 허용)
            instMbrId = existing.get().getInstMbrId();
            registeredAt = existing.get().getRegisteredAt();
            log.info("[QIM-SP] REGISTER 기존 매핑 재사용 instMbrId={} correlationId={}",
                    instMbrId, correlationId);
        } else {
            // 신규 등록
            instMbrId = UuidV7.generate();
            registeredAt = Instant.now();

            InstMbrIdMapping.RegMode regMode = request.isTransfer()
                    ? InstMbrIdMapping.RegMode.TRANSFER
                    : InstMbrIdMapping.RegMode.NEW;
            InstMbrIdMapping.MemberType memberType = request.isCorporate()
                    ? InstMbrIdMapping.MemberType.CORPORATE
                    : InstMbrIdMapping.MemberType.PERSONAL;

            InstMbrIdMapping newMapping = InstMbrIdMapping.builder()
                    .instMbrId(instMbrId)
                    .qimUserId(instMbrId)          // instMbrId = qimUserId (설계 원칙)
                    .mbrUuid(effectiveMbrUuid)
                    .mbrNo(request.getEffectiveMbrNo())
                    .identifierHash(identifierHash)
                    .memberType(memberType)
                    .status(InstMbrIdMapping.MappingStatus.ACTIVE)
                    .regMode(regMode)
                    .registeredAt(registeredAt)
                    .build();

            mappingRepository.save(newMapping);
            log.info("[QIM-SP] REGISTER 신규 매핑 생성 instMbrId={} regMode={} correlationId={}",
                    instMbrId, regMode, correlationId);
        }

        // 4. Outbox 발행 (비동기 — qim.user.events)
        // 이벤트 타입: isTransfer × isCorporate 조합으로 4종 분기 (QIM-OUTBOX-SPEC-001)
        String eventType = resolveRegisterEventType(request.isTransfer(), request.isCorporate());
        publishToOutbox(instMbrId, instMbrId, eventType,
                buildRegisterPayload(instMbrId, request, identifierHash), correlationId);
        log.info("[QIM-SP] REGISTER Outbox 발행 instMbrId={} eventType={} correlationId={}",
                instMbrId, eventType, correlationId);

        // 5. 응답 + 멱등성 저장
        QimSpResponse<QimSpResponse.RegisterData> response = QimSpResponse.ok(
                QimSpResponse.RegisterData.of(instMbrId, registeredAt),
                "등록 완료");

        idempotencyStore.save(idempotencyKey, ENDPOINT_REGISTER, 200,
                serializeResponse(response), correlationId);

        return response;
    }

    // ── MEMBER_WITHDRAW ──────────────────────────────────────────────────────

    /**
     * 회원 탈퇴 수신 처리 (MEMBER_WITHDRAW)
     * Q-IM 명세 §4.4: 이미 탈퇴된 회원에 대한 재호출은 성공으로 처리 (멱등)
     */
    @Transactional
    public QimSpResponse<QimSpResponse.WithdrawData> handleMemberWithdraw(
            QimSpMemberWithdrawRequest request,
            String idempotencyKey,
            String correlationId) {

        // 1. 멱등성 확인
        Optional<SpReceiverIdempotencyStore.StoredResponse> cached =
                idempotencyStore.find(idempotencyKey);
        if (cached.isPresent()) {
            log.info("[QIM-SP] WITHDRAW 멱등 재호출 key={}", idempotencyKey);
            return deserializeResponse(cached.get().responseJson());
        }

        // 2. instMbrId(=mbrId)로 매핑 조회
        String instMbrId = request.getMbrId();
        Optional<InstMbrIdMapping> mappingOpt = mappingRepository.findByInstMbrId(instMbrId);

        if (mappingOpt.isEmpty()) {
            log.warn("[QIM-SP] WITHDRAW 매핑 없음 instMbrId={} correlationId={}", instMbrId, correlationId);
            return QimSpResponse.error("SP_MEMBER_NOT_FOUND",
                    "해당 instMbrId로 등록된 회원을 찾을 수 없습니다.");
        }

        InstMbrIdMapping mapping = mappingOpt.get();
        Instant withdrawnAt;

        if (mapping.isWithdrawn()) {
            // 이미 탈퇴 처리된 회원 — 멱등으로 성공 처리 (ALREADY_WITHDRAWN)
            withdrawnAt = mapping.getWithdrawnAt() != null ? mapping.getWithdrawnAt() : Instant.now();
            log.info("[QIM-SP] WITHDRAW 이미 탈퇴 처리된 회원 instMbrId={} correlationId={}",
                    instMbrId, correlationId);
        } else {
            // 3. 탈퇴 처리
            withdrawnAt = Instant.now();
            mappingRepository.markWithdrawn(instMbrId);

            // 4. FeSession 전체 무효화 (보안: 세션 즉시 만료)
            try {
                feSessionService.invalidateByQimUserId(mapping.getQimUserId(), correlationId);
                log.info("[QIM-SP] FeSession 무효화 qimUserId={}", mapping.getQimUserId());
            } catch (Exception e) {
                log.warn("[QIM-SP] FeSession 무효화 실패 (비치명적) qimUserId={} cause={}",
                        mapping.getQimUserId(), e.getMessage());
            }

            // 5. Outbox 발행 — MEMBER_WITHDRAWN (QIM-OUTBOX-SPEC-001)
            publishToOutbox(instMbrId, mapping.getQimUserId(), EVENT_MEMBER_WITHDRAWN,
                    buildWithdrawPayload(instMbrId, request), correlationId);

            log.info("[QIM-SP] WITHDRAW 처리 완료 instMbrId={} correlationId={}",
                    instMbrId, correlationId);
        }

        // 6. 응답 + 멱등성 저장
        QimSpResponse<QimSpResponse.WithdrawData> response = QimSpResponse.ok(
                QimSpResponse.WithdrawData.of(withdrawnAt),
                mapping.isWithdrawn() ? "이미 탈퇴 처리된 회원입니다" : "탈퇴 처리 완료");

        idempotencyStore.save(idempotencyKey, ENDPOINT_WITHDRAW, 200,
                serializeResponse(response), correlationId);

        return response;
    }

    // ── API Key 검증 ─────────────────────────────────────────────────────────

    /**
     * Q-IM 아웃바운드 API Key 검증 — PBKDF2-HMAC-SHA256 해시 비교
     *
     * <p>저장 포맷: {@code pbkdf2:{iterations}:{saltBase64}:{hashBase64}}
     * 환경변수 {@code QIM_INBOUND_API_KEY_HASH}에 PBKDF2 해시값을 설정해야 한다.
     *
     * <p>보안 원칙:
     * <ul>
     *   <li>CHANGEME 또는 미설정 시 검증 즉시 실패 (우회 경로 완전 제거)</li>
     *   <li>상수 시간 비교로 타이밍 공격 방지 ({@link ApiKeyHashUtil#verify})</li>
     *   <li>검증 실패 사유는 로그에 상세히 남기지 않음 (열거 공격 방지)</li>
     * </ul>
     *
     * @param apiKey Q-IM이 전송한 API Key (X-Api-Key 헤더값)
     * @return 유효하면 {@code true}
     */
    public boolean isValidApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[QIM-SP] API Key 검증 실패: 빈 값");
            return false;
        }

        // CHANGEME sentinel 즉각 거부 (운영 환경 기동 차단)
        if ("CHANGEME".equals(inboundApiKeyHash) || !ApiKeyHashUtil.isPbkdf2Format(inboundApiKeyHash)) {
            log.error("[QIM-SP][보안경고] QIM_INBOUND_API_KEY_HASH가 PBKDF2 포맷이 아닙니다. " +
                      "운영 전 반드시 pbkdf2:...:{salt}:{hash} 형식으로 설정하세요. 현재 모든 요청 거부.");
            return false;
        }

        boolean valid = ApiKeyHashUtil.verify(apiKey, inboundApiKeyHash);
        if (!valid) {
            log.warn("[QIM-SP] API Key 검증 실패: 해시 불일치");
        }
        return valid;
    }

    // ── 내부 유틸 ─────────────────────────────────────────────────────────────

    private String safeDecrypt(String encCi, String correlationId) {
        try {
            return aesDecryptor.decrypt(encCi);
        } catch (AesSharedKeyDecryptor.QimDecryptionException e) {
            log.error("[QIM-SP] AES 복호화 실패 correlationId={} cause={}", correlationId, e.getMessage());
            throw e;
        }
    }

    /**
     * isTransfer × isCorporate 조합 → 4종 이벤트 타입 결정
     *
     * <p>QIM-OUTBOX-SPEC-001 §1 이벤트 & 토픽 매핑 테이블 기준.
     */
    static String resolveRegisterEventType(boolean isTransfer, boolean isCorporate) {
        if (isTransfer && isCorporate)   return EVENT_BIZ_MEMBER_CONVERTED;
        if (!isTransfer && isCorporate)  return EVENT_BIZ_MEMBER_REGISTERED;
        if (isTransfer)                  return EVENT_PERSONAL_MEMBER_CONVERTED;
        return EVENT_PERSONAL_MEMBER_REGISTERED;
    }

    private void publishToOutbox(String instMbrId, String qimUserId,
                                  String eventType, Map<String, Object> payloadMap,
                                  String correlationId) {
        try {
            String payloadJson = objectMapper.writeValueAsString(payloadMap);
            String eventId = UuidV7.generate();

            jdbcTemplate.update(
                    "INSERT INTO ido.outbox "
                    + "(event_id, event_type, partition_key, aggregate_id, event_version, "
                    + " payload, topic, status, retry_count, created_at) "
                    + "VALUES (?, ?, ?, ?, 1, ?::jsonb, ?, 'PENDING', 0, NOW())",
                    eventId, eventType, qimUserId, instMbrId,
                    payloadJson, TOPIC_QIM_USER_EVENTS);

        } catch (JsonProcessingException e) {
            log.error("[QIM-SP] Outbox 직렬화 실패 eventType={} cause={}", eventType, e.getMessage());
        } catch (Exception e) {
            log.error("[QIM-SP] Outbox 적재 실패 eventType={} cause={}", eventType, e.getMessage());
            // Outbox 실패는 SP 수신 응답 실패로 전파하지 않음 (at-least-once 재처리로 복구)
        }
    }

    private Map<String, Object> buildRegisterPayload(String instMbrId,
                                                       QimSpMemberRegisterRequest req,
                                                       String identifierHash) {
        // memberType: BIZ | PERSONAL (QIM-OUTBOX-SPEC-001 페이로드 스펙 기준)
        String memberType = req.isCorporate() ? "BIZ" : "PERSONAL";
        return Map.of(
                "instMbrId",      instMbrId,
                "mbrUuid",        req.getEffectiveMbrUuid() != null ? req.getEffectiveMbrUuid() : "",
                "memberType",     memberType,
                "identifierHash", identifierHash != null ? identifierHash : ""
        );
    }

    private Map<String, Object> buildWithdrawPayload(String instMbrId,
                                                      QimSpMemberWithdrawRequest req) {
        return Map.of(
                "instMbrId", instMbrId,
                "mbrUuid", req.getMbrUuid() != null ? req.getMbrUuid() : "",
                "withdrawalReason", req.getWithdrawalReason() != null ? req.getWithdrawalReason() : ""
        );
    }

    @SuppressWarnings("unchecked")
    private <T> QimSpResponse<T> deserializeResponse(String json) {
        try {
            return objectMapper.readValue(json, QimSpResponse.class);
        } catch (JsonProcessingException e) {
            log.warn("[QIM-SP] 저장된 응답 역직렬화 실패 — 재처리: {}", e.getMessage());
            return null;
        }
    }

    private String serializeResponse(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            log.warn("[QIM-SP] 응답 직렬화 실패: {}", e.getMessage());
            return "{}";
        }
    }
}
