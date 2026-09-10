package io.github.hipstermin.idem.hub.qim.sp.api;

import io.github.hipstermin.idem.common.util.CorrelationIdHolder;
import io.github.hipstermin.idem.hub.qim.crypto.AesSharedKeyDecryptor;
import io.github.hipstermin.idem.hub.qim.sp.api.dto.*;
import io.github.hipstermin.idem.hub.qim.sp.service.QimSpReceiverService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Q-IM SP 수신 API 컨트롤러
 *
 * Q-IM 명세서 v1.52 §4 — SP가 구현하는 수신 API (Q-IM이 SP를 호출)
 * IdO가 Q-IM의 SP 역할을 대리 수행한다.
 *
 * Base URL: /api/qim/sp/v1
 *
 * 엔드포인트:
 *   POST /api/qim/sp/v1/member/query     — 회원 조회 수신 (MEMBER_QUERY)
 *   POST /api/qim/sp/v1/member/register  — 회원 등록 수신 (MEMBER_REGISTER)
 *   POST /api/qim/sp/v1/member/withdraw  — 회원 탈퇴 수신 (MEMBER_WITHDRAW)
 *
 * Q-IM 콘솔 등록 URL (운영):
 *   https://{ido-host}/api/qim/sp/v1/member/{query|register|withdraw}
 *
 * @see <a href="docs/qim-sp-receiver-api-spec.md">SP 수신 API 명세서</a>
 */
@Slf4j
@RestController
@RequestMapping("/api/qim/sp/v1")
@RequiredArgsConstructor
public class QimSpReceiverController {

    private final QimSpReceiverService receiverService;

    // ── MEMBER_QUERY ─────────────────────────────────────────────────────────

    /**
     * 회원 조회 수신 (MEMBER_QUERY)
     * Q-IM이 전환/탈퇴 처리 전 SP에 회원 존재 여부를 확인한다.
     */
    @PostMapping("/member/query")
    public ResponseEntity<?> memberQuery(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestBody QimSpMemberQueryRequest request) {

        String cid = resolveCorrelationId(correlationId);
        log.info("[QIM-SP] MEMBER_QUERY 수신 correlationId={} idempotencyKey={}", cid, idempotencyKey);

        // API Key 검증
        ResponseEntity<?> authError = validateApiKey(apiKey, cid);
        if (authError != null) return authError;

        // 입력값 검증
        if (request.getRegType() == null) {
            return errorResponse(HttpStatus.BAD_REQUEST, "SP_INPUT_INVALID", "regType 필드가 필요합니다.");
        }
        if (request.isPersonal() && (request.getEncCi() == null || request.getEncCi().isBlank())) {
            return errorResponse(HttpStatus.BAD_REQUEST, "SP_INPUT_INVALID", "개인회원 조회에는 encCi가 필요합니다.");
        }
        if (request.isCorporate() && (request.getBrno() == null || request.getBrno().isBlank())) {
            return errorResponse(HttpStatus.BAD_REQUEST, "SP_INPUT_INVALID", "기업회원 조회에는 brno가 필요합니다.");
        }

        try {
            QimSpResponse<QimSpResponse.QueryData> response =
                    receiverService.handleMemberQuery(request, idempotencyKey, cid);
            return ResponseEntity.ok(response);

        } catch (AesSharedKeyDecryptor.QimDecryptionException e) {
            log.warn("[QIM-SP] QUERY AES 복호화 실패 correlationId={}", cid);
            return errorResponse(HttpStatus.UNPROCESSABLE_ENTITY, "SP_DECRYPT_FAILED",
                    "encCi 복호화에 실패했습니다. AES 공유키를 확인해주세요.");
        } catch (Exception e) {
            log.error("[QIM-SP] QUERY 처리 중 오류 correlationId={} cause={}", cid, e.getMessage(), e);
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "SP_INTERNAL_ERROR",
                    "서버 내부 오류가 발생했습니다.");
        }
    }

    // ── MEMBER_REGISTER ──────────────────────────────────────────────────────

    /**
     * 회원 등록 수신 (MEMBER_REGISTER)
     * Q-IM이 회원을 저장한 후 SP(=IdO)에 회원 정보를 통보한다.
     * regMode=NEW(신규) 또는 regMode=TRANSFER(전환) 두 가지 모드.
     */
    @PostMapping("/member/register")
    public ResponseEntity<?> memberRegister(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestBody QimSpMemberRegisterRequest request) {

        String cid = resolveCorrelationId(correlationId);
        log.info("[QIM-SP] MEMBER_REGISTER 수신 regMode={} correlationId={} idempotencyKey={}",
                request.getRegMode(), cid, idempotencyKey);

        // API Key 검증
        ResponseEntity<?> authError = validateApiKey(apiKey, cid);
        if (authError != null) return authError;

        // 입력값 검증
        if (request.getEffectiveMbrUuid() == null) {
            return errorResponse(HttpStatus.BAD_REQUEST, "SP_INPUT_INVALID",
                    "mbrUuid 또는 entMbrUuid 필드가 필요합니다.");
        }
        if (!request.isCorporate() && (request.getEncCi() == null || request.getEncCi().isBlank())) {
            return errorResponse(HttpStatus.BAD_REQUEST, "SP_INPUT_INVALID",
                    "개인회원 등록에는 encCi가 필요합니다.");
        }

        try {
            QimSpResponse<QimSpResponse.RegisterData> response =
                    receiverService.handleMemberRegister(request, idempotencyKey, cid);
            return ResponseEntity.ok(response);

        } catch (AesSharedKeyDecryptor.QimDecryptionException e) {
            log.warn("[QIM-SP] REGISTER AES 복호화 실패 correlationId={}", cid);
            return errorResponse(HttpStatus.UNPROCESSABLE_ENTITY, "SP_DECRYPT_FAILED",
                    "encCi 복호화에 실패했습니다. AES 공유키를 확인해주세요.");
        } catch (Exception e) {
            log.error("[QIM-SP] REGISTER 처리 중 오류 correlationId={} cause={}", cid, e.getMessage(), e);
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "SP_INTERNAL_ERROR",
                    "서버 내부 오류가 발생했습니다.");
        }
    }

    // ── MEMBER_WITHDRAW ──────────────────────────────────────────────────────

    /**
     * 회원 탈퇴 수신 (MEMBER_WITHDRAW)
     * 이용자 본인 또는 운영자 주도 탈퇴 시 Q-IM이 모든 활성 SP에 전파한다.
     * 이미 탈퇴된 회원에 대한 재호출은 성공으로 처리 (멱등, ALREADY_WITHDRAWN).
     */
    @PostMapping("/member/withdraw")
    public ResponseEntity<?> memberWithdraw(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestBody QimSpMemberWithdrawRequest request) {

        String cid = resolveCorrelationId(correlationId);
        log.info("[QIM-SP] MEMBER_WITHDRAW 수신 instMbrId={} correlationId={} idempotencyKey={}",
                request.getMbrId(), cid, idempotencyKey);

        // API Key 검증
        ResponseEntity<?> authError = validateApiKey(apiKey, cid);
        if (authError != null) return authError;

        // 입력값 검증
        if (request.getMbrId() == null || request.getMbrId().isBlank()) {
            return errorResponse(HttpStatus.BAD_REQUEST, "SP_INPUT_INVALID",
                    "mbrId(instMbrId) 필드가 필요합니다.");
        }

        try {
            QimSpResponse<QimSpResponse.WithdrawData> response =
                    receiverService.handleMemberWithdraw(request, idempotencyKey, cid);

            // 404: 매핑 없는 회원
            if (response != null && !response.isSuccess()
                    && "SP_MEMBER_NOT_FOUND".equals(response.getErrorCode())) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }
            return ResponseEntity.ok(response);

        } catch (AesSharedKeyDecryptor.QimDecryptionException e) {
            log.warn("[QIM-SP] WITHDRAW AES 복호화 실패 correlationId={}", cid);
            return errorResponse(HttpStatus.UNPROCESSABLE_ENTITY, "SP_DECRYPT_FAILED",
                    "encCi 복호화에 실패했습니다.");
        } catch (Exception e) {
            log.error("[QIM-SP] WITHDRAW 처리 중 오류 correlationId={} cause={}", cid, e.getMessage(), e);
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "SP_INTERNAL_ERROR",
                    "서버 내부 오류가 발생했습니다.");
        }
    }

    // ── 내부 유틸 ─────────────────────────────────────────────────────────────

    private ResponseEntity<?> validateApiKey(String apiKey, String correlationId) {
        if (!receiverService.isValidApiKey(apiKey)) {
            log.warn("[QIM-SP] API Key 인증 실패 correlationId={}", correlationId);
            return errorResponse(HttpStatus.UNAUTHORIZED, "SP_AUTH_INVALID",
                    "API Key 인증에 실패했습니다.");
        }
        return null;
    }

    private ResponseEntity<QimSpResponse<?>> errorResponse(
            HttpStatus status, String errorCode, String message) {
        return ResponseEntity.status(status)
                .body(QimSpResponse.error(errorCode, message));
    }

    private String resolveCorrelationId(String correlationId) {
        return (correlationId != null && !correlationId.isBlank())
                ? correlationId
                : CorrelationIdHolder.generate();
    }
}
