package io.github.hipstermin.idem.registry.consent;

import java.util.List;

/**
 * 개인정보 동의 서비스 인터페이스 (P2 §12.3)
 */
public interface ConsentService {

    /**
     * 동의 기록 — 사용자가 지정 버전에 동의
     *
     * @param qimUserId   동의 사용자 ID
     * @param request     동의 요청 (유형, 버전ID, 경로 등)
     * @return 동의 처리 결과
     */
    ConsentResult agree(String qimUserId, ConsentRequest request);

    /**
     * 동의 철회 — 선택 동의(required=false)만 철회 가능
     *
     * @param qimUserId   철회 사용자 ID
     * @param consentType 철회할 동의 유형
     * @param reason      철회 사유
     * @param correlationId 흐름 추적 ID
     * @return 철회 처리 결과
     */
    ConsentResult withdraw(String qimUserId, String consentType,
                            String reason, String correlationId);

    /**
     * 사용자 동의 현황 조회
     *
     * @param qimUserId 조회 사용자 ID
     * @return 동의 유형별 최신 상태 목록
     */
    List<ConsentResult> getConsentStatus(String qimUserId);

    /**
     * 최신 동의 버전 목록 조회 (회원가입/재동의 화면 로딩)
     */
    List<ConsentVersionInfo> getActiveVersions();

    // ── 1.1 동의 카탈로그 (플랜 §5 #8) ───────────────────────────────────────

    /** 서비스가 보는 카탈로그 — 플랫폼 공통 + 서비스 전용, ACTIVE·적용 중 */
    List<ConsentVersionInfo> catalog(String serviceCode);

    /** 한 범위의 버전 목록 — serviceCode null 이면 플랫폼 공통. includeInactive 면 SUPERSEDED 이력까지 */
    List<ConsentVersionInfo> listVersions(String serviceCode, boolean includeInactive);

    /** 새 버전 발행 — 같은 범위·유형의 ACTIVE 는 SUPERSEDED 로. 사용자는 새 버전에 다시 동의해야 한다 */
    ConsentVersionInfo publish(PublishRequest request);

    /** 버전 종료(카탈로그에서 뺀다) — ACTIVE → SUPERSEDED, 대체 없음 */
    ConsentVersionInfo retire(String versionId, String correlationId);

    /** 사용자가 아직 동의하지 않은 카탈로그 항목(버전 단위) — 필수·선택 모두 */
    List<ConsentVersionInfo> missing(String qimUserId, String serviceCode);

    /** 발행 요청 — consentType 은 영문 대문자·숫자·_ (2~50자), 유형은 자유(TERMS_OF_SERVICE·PRIVACY_POLICY·THIRD_PARTY_SHARE·MARKETING 또는 서비스 고유) */
    record PublishRequest(String serviceCode, String consentType, String versionTag, String title, String contentUrl,
                          Boolean required, java.time.Instant effectiveAt, String correlationId) {}
}
