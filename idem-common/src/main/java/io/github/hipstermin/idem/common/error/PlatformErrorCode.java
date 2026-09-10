package io.github.hipstermin.idem.common.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 플랫폼 표준 오류 코드
 * 설계서 6.3 / 8.11 / 16.12절 참조
 */
@Getter
@RequiredArgsConstructor
public enum PlatformErrorCode {

    // ── Q-Sign 오류 (E-QS-xxx) ───────────────────────────────────────────────
    QS_AUTH_FAILED        ("E-QS-001", HttpStatus.UNAUTHORIZED,   "인증에 실패했습니다."),
    QS_AUTH_LOCKED        ("E-QS-002", HttpStatus.TOO_MANY_REQUESTS, "인증 잠금 상태입니다."),
    QS_PROVIDER_TIMEOUT   ("E-QS-003", HttpStatus.GATEWAY_TIMEOUT,"외부 인증 사업자 응답 시간 초과."),

    // ── 브로커 오류 (E-IDP-4xx) — 비OIDC/반표준 정규화 경로 전용 ────────────
    // 설계서 §6.3.1 브로커 오류 코드 상세 정의 참조
    IDP_PROVIDER_UNAVAILABLE    ("E-IDP-401", HttpStatus.BAD_GATEWAY,          "인증 사업자 연결 불가."),
    IDP_RESPONSE_INVALID        ("E-IDP-402", HttpStatus.BAD_GATEWAY,          "인증 사업자 응답 형식 오류."),
    IDP_SIGNATURE_MISMATCH      ("E-IDP-403", HttpStatus.UNPROCESSABLE_ENTITY, "사업자 응답 서명 불일치."),
    // E-IDP-404: Provider Registry 미등록 providerCode — §24.4.1 체크리스트 항목
    IDP_PROVIDER_NOT_REGISTERED ("E-IDP-404", HttpStatus.NOT_FOUND,            "등록되지 않은 인증 사업자 코드입니다."),
    // E-IDP-405: Circuit Breaker OPEN 상태 (이전 E-IDP-404에서 변경)
    IDP_CIRCUIT_OPEN            ("E-IDP-405", HttpStatus.SERVICE_UNAVAILABLE,  "Circuit Breaker OPEN 상태 — 해당 인증 사업자 일시 차단."),
    // E-OPS-901: 외부 시스템 장애 (Retry-After 헤더 필수 — §17.5)
    OPS_EXTERNAL_SYSTEM_ERROR   ("E-OPS-901", HttpStatus.BAD_GATEWAY,          "외부 인증 사업자 장애."),

    // ── Q-IM 오류 (E-IM-2xx) ─────────────────────────────────────────────────
    IM_USER_NOT_FOUND         ("E-IM-201", HttpStatus.NOT_FOUND,      "사용자 정본을 찾을 수 없습니다."),
    IM_USER_SUSPENDED         ("E-IM-202", HttpStatus.FORBIDDEN,      "정지 상태 사용자입니다."),
    IM_USER_WITHDRAWN         ("E-IM-203", HttpStatus.GONE,           "탈퇴한 사용자입니다."),
    IM_IDENTIFIER_CONFLICT    ("E-IM-204", HttpStatus.CONFLICT,       "인증수단 중복 매핑 오류."),
    IM_WITHDRAWAL_ALREADY     ("E-IM-205", HttpStatus.CONFLICT,       "이미 탈퇴 처리 중이거나 완료된 사용자입니다."),
    IM_WITHDRAWAL_NOT_ALLOWED ("E-IM-206", HttpStatus.FORBIDDEN,      "현재 상태에서 탈퇴가 허용되지 않습니다."),
    IM_CONSENT_NOT_FOUND      ("E-IM-207", HttpStatus.NOT_FOUND,      "동의 기록을 찾을 수 없습니다."),
    IM_CONSENT_VERSION_INVALID("E-IM-208", HttpStatus.CONFLICT,       "동의 버전이 유효하지 않습니다."),
    IM_CONVERSION_NOT_FOUND   ("E-IM-209", HttpStatus.NOT_FOUND,      "전환 세션을 찾을 수 없습니다."),
    IM_CONVERSION_EXPIRED     ("E-IM-210", HttpStatus.GONE,           "전환 세션이 만료되었습니다."),
    IM_CONVERSION_INVALID_STATE("E-IM-211", HttpStatus.CONFLICT,      "전환 세션 상태 전이가 유효하지 않습니다."),

    // ── 보호자 인증 오류 (E-IM-212~214) ───────────────────────────────────────
    IM_MINOR_GUARDIAN_REQUIRED  ("E-IM-212", HttpStatus.FORBIDDEN,    "14세 미만 회원은 보호자 동의가 필요합니다."),
    IM_GUARDIAN_NOT_FOUND       ("E-IM-213", HttpStatus.NOT_FOUND,    "보호자 정보를 찾을 수 없습니다."),
    IM_GUARDIAN_CONSENT_ALREADY ("E-IM-214", HttpStatus.CONFLICT,     "이미 보호자 동의가 완료된 계정입니다."),

    // ── 기업회원 전환 오류 (E-IM-215~217) ──────────────────────────────────────
    IM_BIZ_REG_INVALID          ("E-IM-215", HttpStatus.BAD_REQUEST,  "유효하지 않은 사업자등록번호 형식입니다."),
    IM_BIZ_REG_DUPLICATE        ("E-IM-216", HttpStatus.CONFLICT,     "이미 등록된 사업자등록번호입니다."),
    IM_BIZ_MEMBER_NOT_FOUND     ("E-IM-217", HttpStatus.NOT_FOUND,    "기업회원 정보를 찾을 수 없습니다."),

    // ── IdO 오류 (E-IDO-1xx) ─────────────────────────────────────────────────
    IDO_TICKET_EXPIRED    ("E-IDO-101", HttpStatus.GONE,           "Handoff Ticket이 만료되었습니다."),
    IDO_TICKET_CONSUMED   ("E-IDO-102", HttpStatus.CONFLICT,       "이미 소비된 Ticket입니다."),
    IDO_TICKET_REVOKED    ("E-IDO-103", HttpStatus.GONE,           "취소된 Ticket입니다."),
    IDO_POLICY_REJECTED   ("E-IDO-104", HttpStatus.FORBIDDEN,      "정책 검증에서 거부되었습니다."),
    IDO_AUTH_LEVEL_INSUFFICIENT("E-IDO-105", HttpStatus.FORBIDDEN, "인증 수준이 부족합니다."),
    IDO_QIM_UNREACHABLE   ("E-IDO-106", HttpStatus.SERVICE_UNAVAILABLE, "Q-IM 조회 실패 — 안전 우선 거부."),
    // E-IDO-107: FE 세션 없음/만료 — P1 수정: Handoff 발급 시 feSession 쿠키 검증 실패
    IDO_SESSION_NOT_FOUND ("E-IDO-107", HttpStatus.UNAUTHORIZED,       "FE 세션이 없거나 만료되었습니다. 재인증 필요."),
    // E-IDO-108: Sprint α-2 / F4.1 — Handoff verify 시 HMAC 서명/AAD 검증 실패
    IDO_TICKET_SIGNATURE_INVALID("E-IDO-108", HttpStatus.UNAUTHORIZED, "Handoff Ticket 서명 검증 실패."),
    // ── 본인인증 SPI (docs/vendor-plugin-plan.md P1) ─────────────────────────
    IDO_AUTH_PROVIDER_UNKNOWN("E-IDO-109", HttpStatus.NOT_FOUND, "등록되지 않은 본인인증 제공자입니다."),
    IDO_AUTH_VERIFICATION_FAILED("E-IDO-110", HttpStatus.BAD_REQUEST, "본인인증에 실패했습니다."),
    // ── 범용화 S1 (docs/generalization-plan.md) ──────────────────────────────
    IDO_INVALID_INTEGRATION_TYPE("E-IDO-111", HttpStatus.BAD_REQUEST, "지원하지 않는 기관 연동 유형입니다."),
    IDO_PROVIDER_NOT_CONFIGURED("E-IDO-112", HttpStatus.SERVICE_UNAVAILABLE, "인증 제공자가 설정되지 않았습니다."),

    // ── 기관 오류 (E-AGENCY-3xx) ─────────────────────────────────────────────
    AGENCY_NOT_REGISTERED ("E-AGENCY-301", HttpStatus.FORBIDDEN,   "등록되지 않은 기관 코드입니다."),
    AGENCY_CODE_MISMATCH  ("E-AGENCY-302", HttpStatus.FORBIDDEN,   "기관 코드 불일치."),
    AGENCY_KEY_INVALID    ("E-AGENCY-303", HttpStatus.UNAUTHORIZED, "기관 API Key 인증 실패."),
    AGENCY_CALLBACK_BLOCKED("E-AGENCY-304", HttpStatus.FORBIDDEN,  "허용되지 않은 콜백 URL."),
    AGENCY_MAINTENANCE    ("E-AGENCY-305", HttpStatus.SERVICE_UNAVAILABLE, "기관 점검 시간입니다."),
    AGENCY_RATE_LIMIT_EXCEEDED("E-AGENCY-306", HttpStatus.TOO_MANY_REQUESTS, "요청 한도를 초과했습니다."),
    AGENCY_NOT_FOUND      ("E-AGENCY-307", HttpStatus.NOT_FOUND,   "기관을 찾을 수 없거나 비활성 상태입니다."),

    // ── 전환(Conversion) 오류 (E-CONV-6xx) — GUIDE-002 signed_request 방식 ────
    CONVERSION_SIGNATURE_INVALID("E-CONV-601", HttpStatus.UNAUTHORIZED,  "전환 요청 JWT 서명 검증 실패 — 기관 API Key 불일치."),
    CONVERSION_REQUEST_EXPIRED  ("E-CONV-602", HttpStatus.GONE,          "전환 요청이 만료되었습니다 (5분 초과). 기관 시스템에서 재시도하세요."),
    CONVERSION_SESSION_NOT_FOUND("E-CONV-603", HttpStatus.NOT_FOUND,     "전환 세션을 찾을 수 없거나 만료되었습니다."),

    // ── Cross-Agency SSO — CAST 토큰 오류 (E-SSO-CAST-4xx) ──────────────────
    // Sprint 13 신규: 기관 간 SSO 1회성 토큰 검증 오류 (§13.3)
    SSO_CAST_EXPIRED          ("E-SSO-CAST-401", HttpStatus.GONE,                  "CAST 토큰이 만료되었습니다."),
    SSO_CAST_CONSUMED         ("E-SSO-CAST-402", HttpStatus.CONFLICT,              "이미 사용된 CAST 토큰입니다."),
    SSO_CAST_AGENCY_MISMATCH  ("E-SSO-CAST-403", HttpStatus.FORBIDDEN,            "CAST 토큰의 대상 기관이 일치하지 않습니다."),
    SSO_CAST_SIGNATURE_INVALID("E-SSO-CAST-404", HttpStatus.UNAUTHORIZED,         "CAST 토큰 서명 검증에 실패했습니다."),
    SSO_CAST_SESSION_NOT_FOUND("E-SSO-CAST-405", HttpStatus.UNAUTHORIZED,         "유효한 FE 세션이 없습니다. 재인증이 필요합니다."),
    SSO_CAST_ISSUE_FAILED     ("E-SSO-CAST-406", HttpStatus.INTERNAL_SERVER_ERROR, "CAST 토큰 발급 중 오류가 발생했습니다."),

    // ── 프로비저닝 오류 (E-PROV-5xx) ─────────────────────────────────────────
    // Sprint 14 신규: 전 기관 프로비저닝 관련 오류 (§14.3)
    PROV_AGENCY_ENDPOINT_NOT_FOUND("E-PROV-501", HttpStatus.NOT_FOUND,             "기관 엔드포인트가 등록되지 않았습니다."),
    PROV_OUTBOX_DEAD_LETTER       ("E-PROV-502", HttpStatus.INTERNAL_SERVER_ERROR,  "프로비저닝 최대 재시도 초과 — DEAD_LETTER."),
    PROV_IDEMPOTENCY_CONFLICT     ("E-PROV-503", HttpStatus.CONFLICT,              "중복 프로비저닝 요청 (멱등 키 충돌)."),
    PROV_INBOUND_REJECTED         ("E-PROV-504", HttpStatus.UNPROCESSABLE_ENTITY,  "기관 인바운드 데이터 검증 실패.");

    private final String code;
    private final HttpStatus httpStatus;
    private final String defaultMessage;
}
