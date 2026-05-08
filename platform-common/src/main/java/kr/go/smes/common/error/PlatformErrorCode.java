package kr.go.smes.common.error;

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
    IM_USER_NOT_FOUND     ("E-IM-201", HttpStatus.NOT_FOUND,      "사용자 정본을 찾을 수 없습니다."),
    IM_USER_SUSPENDED     ("E-IM-202", HttpStatus.FORBIDDEN,      "정지 상태 사용자입니다."),
    IM_USER_WITHDRAWN     ("E-IM-203", HttpStatus.GONE,           "탈퇴한 사용자입니다."),
    IM_IDENTIFIER_CONFLICT("E-IM-204", HttpStatus.CONFLICT,       "인증수단 중복 매핑 오류."),

    // ── IdO 오류 (E-IDO-1xx) ─────────────────────────────────────────────────
    IDO_TICKET_EXPIRED    ("E-IDO-101", HttpStatus.GONE,           "Handoff Ticket이 만료되었습니다."),
    IDO_TICKET_CONSUMED   ("E-IDO-102", HttpStatus.CONFLICT,       "이미 소비된 Ticket입니다."),
    IDO_TICKET_REVOKED    ("E-IDO-103", HttpStatus.GONE,           "취소된 Ticket입니다."),
    IDO_POLICY_REJECTED   ("E-IDO-104", HttpStatus.FORBIDDEN,      "정책 검증에서 거부되었습니다."),
    IDO_AUTH_LEVEL_INSUFFICIENT("E-IDO-105", HttpStatus.FORBIDDEN, "인증 수준이 부족합니다."),
    IDO_QIM_UNREACHABLE   ("E-IDO-106", HttpStatus.SERVICE_UNAVAILABLE, "Q-IM 조회 실패 — 안전 우선 거부."),

    // ── 기관 오류 (E-AGENCY-3xx) ─────────────────────────────────────────────
    AGENCY_NOT_REGISTERED ("E-AGENCY-301", HttpStatus.FORBIDDEN,   "등록되지 않은 기관 코드입니다."),
    AGENCY_CODE_MISMATCH  ("E-AGENCY-302", HttpStatus.FORBIDDEN,   "기관 코드 불일치."),
    AGENCY_KEY_INVALID    ("E-AGENCY-303", HttpStatus.UNAUTHORIZED, "기관 API Key 인증 실패."),
    AGENCY_CALLBACK_BLOCKED("E-AGENCY-304", HttpStatus.FORBIDDEN,  "허용되지 않은 콜백 URL."),
    AGENCY_MAINTENANCE    ("E-AGENCY-305", HttpStatus.SERVICE_UNAVAILABLE, "기관 점검 시간입니다."),
    AGENCY_RATE_LIMIT_EXCEEDED("E-AGENCY-306", HttpStatus.TOO_MANY_REQUESTS, "요청 한도를 초과했습니다.");

    private final String code;
    private final HttpStatus httpStatus;
    private final String defaultMessage;
}
